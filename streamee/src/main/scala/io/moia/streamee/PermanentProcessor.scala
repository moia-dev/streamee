/*
 * Copyright 2018 MOIA GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.moia.streamee

import akka.{ Done, NotUsed }
import akka.actor.Scheduler
import akka.stream.{
  ActorAttributes,
  Attributes,
  ClosedShape,
  FanInShape2,
  Inlet,
  Materializer,
  Outlet,
  OverflowStrategy,
  Supervision
}
import akka.stream.scaladsl.{ Flow, GraphDSL, Keep, RunnableGraph, Sink, Source, Unzip }
import akka.stream.stage.{
  GraphStage,
  GraphStageLogic,
  InHandler,
  OutHandler,
  TimerGraphStageLogic
}
import akka.stream.QueueOfferResult.{ Dropped, Enqueued }
import io.moia.streamee.Processor.{ ProcessorUnavailable, UnexpectedQueueOfferResult }
import org.apache.logging.log4j.scala.Logging
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.concurrent.duration.FiniteDuration

private object PermanentProcessor extends Logging {

  final class PromisesStage[A, B, C](correlateRequest: A => C,
                                     correlateResponse: B => C,
                                     sweepCompleteResponsesInterval: FiniteDuration)
      extends GraphStage[FanInShape2[(A, Promise[B]), B, NotUsed]]
      with Logging {

    override val shape: FanInShape2[(A, Promise[B]), B, NotUsed] =
      new FanInShape2(Inlet[(A, Promise[B])]("PromisesStage.in0"),
                      Inlet[B]("PromisesStage.in1"),
                      Outlet[NotUsed]("PromisesStage.out"))

    override def createLogic(attributes: Attributes): GraphStageLogic =
      new TimerGraphStageLogic(shape) {
        import shape._

        private var reqToRes = Map.empty[C, Promise[B]]

        setHandler(
          in0,
          new InHandler {
            override def onPush(): Unit = {
              val (req, res) = grab(in0)
              reqToRes += correlateRequest(req) -> res
              if (!hasBeenPulled(in0)) pull(in0)
            }

            // Only in1 must complete the stage!
            override def onUpstreamFinish(): Unit =
              ()
          }
        )

        setHandler(
          in1,
          new InHandler {
            override def onPush(): Unit = {
              val res = grab(in1)
              reqToRes.get(correlateResponse(res)).foreach(_.trySuccess(res))
              push(out, NotUsed)
            }
          }
        )

        setHandler(out, new OutHandler {
          override def onPull(): Unit = {
            if (!isClosed(in0) && !hasBeenPulled(in0)) pull(in0)
            pull(in1)
          }
        })

        override def preStart(): Unit =
          schedulePeriodicallyWithInitialDelay("gc",
                                               sweepCompleteResponsesInterval,
                                               sweepCompleteResponsesInterval)

        override protected def onTimer(timerKey: Any): Unit =
          reqToRes = reqToRes.filter { case (_, res) => !res.isCompleted }
      }
  }

  def resume(name: String)(cause: Throwable): Supervision.Directive = {
    logger.error(s"Processor $name failed and resumes", cause)
    Supervision.Resume
  }
}

private final class PermanentProcessor[A, B, C](
    process: Flow[A, B, Any],
    timeout: FiniteDuration,
    name: String,
    bufferSize: Int,
    sweepCompleteResponsesInterval: FiniteDuration,
    correlateRequest: A => C,
    correlateResponse: B => C
)(implicit ec: ExecutionContext, mat: Materializer, scheduler: Scheduler)
    extends Processor[A, B] {
  import PermanentProcessor._

  private val source =
    Source
      .queue[(A, Promise[B])](bufferSize, OverflowStrategy.dropNew)
      .map { case reqAndRes @ (req, _) => (reqAndRes, req) }

  private val (queue, done) =
    RunnableGraph
      .fromGraph(GraphDSL.create(source, Sink.ignore)(Keep.both) {
        implicit builder => (source, ignore) =>
          import GraphDSL.Implicits._

          val unzip = builder.add(Unzip[(A, Promise[B]), A]())
          val promises =
            builder.add(
              new PromisesStage[A, B, C](correlateRequest,
                                         correlateResponse,
                                         sweepCompleteResponsesInterval)
            )

          // format: off
          source ~> unzip.in
                    unzip.out0  ~>            promises.in0
                    unzip.out1  ~> process ~> promises.in1
                                              promises.out ~> ignore
          // format: on

          ClosedShape
      })
      .withAttributes(ActorAttributes.supervisionStrategy(resume(name)))
      .run()

  override def process(request: A): Future[B] = {
    val promisedResponse = ExpiringPromise[B](timeout, request.toString)
    queue.offer((request, promisedResponse)).flatMap {
      case Enqueued => promisedResponse.future
      case Dropped  => Future.failed(ProcessorUnavailable(name))
      case other    => Future.failed(UnexpectedQueueOfferResult(other))
    }
  }

  override def shutdown(): Future[Done] = {
    queue.complete()
    done
  }
}
