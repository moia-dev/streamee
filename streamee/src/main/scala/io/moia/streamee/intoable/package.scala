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

import akka.actor.Scheduler
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.stream.{ KillSwitch, KillSwitches, Materializer }
import akka.stream.scaladsl.{
  Flow,
  FlowWithContext,
  Keep,
  MergeHub,
  Sink,
  Source,
  FlowOps => AkkaFlowOps
}
import akka.util.Timeout
import akka.Done
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.concurrent.duration.FiniteDuration

package object intoable {

  type IntoableSink[A, B] = Sink[(A, Promise[B]), Any]

  type IntoableProcess[-A, B] = FlowWithContext[Promise[B], A, Promise[B], B, Any]

  type RemotelyIntoableSink[A, B] = Sink[(A, Respondee[B]), Any]

  type RemotelyIntoableProcess[-A, B] = FlowWithContext[Respondee[B], A, Respondee[B], B, Any]

  type Respondee[A] = ActorRef[Respondee.Response[A]]

  type RespondeeFactory[A] = ActorRef[RespondeeFactory.CreateRespondee[A]]

  /**
    * Extension methods for `Source`s.
    */
  implicit final class SourceOps[A, M](val source: Source[A, M]) extends AnyVal {

    /**
      * Input elements into the given `intoableSink` and output responses with the given
      * `parallelism`.
      */
    def into[B](
        intoableSink: IntoableSink[A, B],
        parallelism: Int
    )(implicit ec: ExecutionContext, scheduler: Scheduler): Source[B, M] =
      intoImpl(source, intoableSink, parallelism)

    /**
      * Input elements into the given `remotelyIntoableSink` which is expected to respond within the
      * given `responseTimeout` and output responses with the given `parallelism`.
      */
    def into[B](remotelyIntoableSink: RemotelyIntoableSink[A, B],
                responseTimeout: FiniteDuration,
                parallelism: Int)(implicit ec: ExecutionContext,
                                  scheduler: Scheduler,
                                  respondeeFactory: RespondeeFactory[B]): Source[B, M] =
      intoImpl(source, remotelyIntoableSink, responseTimeout, parallelism)
  }

  /**
    * Extension methods for `Flows`s. Note: `akka.stream.scaladsl.FlowOps` has been renamed
    * `AkkaFlowOps` in the imports.
    */
  implicit final class FlowOps[A, B, M](val flow: Flow[A, B, M]) extends AnyVal {

    /**
      * Input elements into the given `intoableSink` and output responses with the given
      * `parallelism`.
      */
    def into[C](
        intoableSink: IntoableSink[B, C],
        parallelism: Int
    )(implicit ec: ExecutionContext, scheduler: Scheduler): Flow[A, C, M] =
      intoImpl(flow, intoableSink, parallelism)

    /**
      * Input elements into the given `remotelyIntoableSink` which is expected to respond within the
      * given `responseTimeout` and output responses with the given `parallelism`.
      */
    def into[C](remotelyIntoableSink: RemotelyIntoableSink[B, C],
                responseTimeout: FiniteDuration,
                parallelism: Int)(implicit ec: ExecutionContext,
                                  scheduler: Scheduler,
                                  respondeeFactory: RespondeeFactory[C]): Flow[A, C, M] =
      intoImpl(flow, remotelyIntoableSink, responseTimeout, parallelism)
  }

  /**
    * Run the given `intoableProcess` with the given `bufferSize` (must be positive).
    *
    * @return intoable sink to be used with `into` and completion signal (which should not happen)
    */
  def runIntoableProcess[A, B](intoableProcess: IntoableProcess[A, B], bufferSize: Int)(
      implicit mat: Materializer
  ): (IntoableSink[A, B], Future[Done]) =
    MergeHub
      .source[(A, Promise[B])](bufferSize)
      .via(intoableProcess)
      .toMat(Sink.foreach { case (b, p) => p.trySuccess(b) })(Keep.both)
      .run()

  /**
    * Run the given `remotelyIntoableProcess` with the given `bufferSize` (must be positive). Notice
    * that using the returned kill switch might result in dropping (loosing) `bufferSize` number of
    * elements!
    *
    * @return remotely intoable sink to be used with `into`, kill switch and completion signal
    *         (which should not happen except for using the kill switch)
    */
  def runRemotelyIntoableProcess[A, B](
      remotelyIntoableProcess: RemotelyIntoableProcess[A, B],
      bufferSize: Int
  )(implicit mat: Materializer): (RemotelyIntoableSink[A, B], KillSwitch, Future[Done]) =
    MergeHub
      .source[(A, Respondee[B])](bufferSize)
      .viaMat(KillSwitches.single)(Keep.both)
      .via(remotelyIntoableProcess)
      .toMat(Sink.foreach { case (b, r) => r ! Respondee.Response(b) }) {
        case ((s, r), d) => (s, r, d)
      }
      .run()

  private def intoImpl[A, B, M](
      flowOps: AkkaFlowOps[A, M],
      intoableSink: IntoableSink[A, B],
      parallelism: Int
  )(implicit ec: ExecutionContext, scheduler: Scheduler): flowOps.Repr[B] =
    flowOps
      .map(a => (a, Promise[B]()))
      .alsoTo(intoableSink)
      .mapAsync(parallelism)(_._2.future)

  private def intoImpl[A, B, M](
      flowOps: AkkaFlowOps[A, M],
      remotelyIntoableSink: RemotelyIntoableSink[A, B],
      responseTimeout: FiniteDuration,
      parallelism: Int
  )(implicit ec: ExecutionContext,
    scheduler: Scheduler,
    respondeeFactory: RespondeeFactory[B]): flowOps.Repr[B] =
    flowOps
      .mapAsync(parallelism) { a =>
        implicit val askTimeout: Timeout = responseTimeout // let's use the same timeout
        val b                            = Promise[B]()
        def createRespondee(replyTo: ActorRef[RespondeeFactory.RespondeeCreated[B]]) =
          RespondeeFactory.CreateRespondee[B](b, responseTimeout, replyTo, s"a = $a")
        val respondee = (respondeeFactory ? createRespondee).map(_.respondee)
        Future.successful((a, b)).zip(respondee)
      }
      .alsoTo(
        Flow[((A, Promise[B]), Respondee[B])]
          .map { case ((a, _), r) => (a, r) }
          .to(remotelyIntoableSink)
      )
      .mapAsync(parallelism) { case ((_, b), _) => b.future }
}
