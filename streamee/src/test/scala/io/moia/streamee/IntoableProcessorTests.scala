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

import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.{ ActorAttributes, Supervision, ThrottleMode }
import org.scalacheck.Gen
import org.scalatest.{ AsyncWordSpec, Matchers }
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import scala.concurrent.duration.DurationInt

final class IntoableProcessorTests
    extends AsyncWordSpec
    with AkkaSuite
    with Matchers
    with ScalaCheckDrivenPropertyChecks {

  "Creating an IntoableProcessor" should {
    "throw an IllegalArgumentException for bufferSize <= 0" in {
      forAll(Gen.choose(Int.MinValue, 0)) { bufferSize =>
        an[IllegalArgumentException] shouldBe thrownBy {
          IntoableProcessor(Process[Int, Int](), "name", bufferSize)
        }
      }
    }
  }

  "Calling shutdown" should {
    "fail after the given timeout" in {
      val timeout   = 100.milliseconds
      val process   = Process[String, String]().delay(1.second)
      val processor = FrontProcessor(process, timeout, "name")
      processor
        .accept("abc")
        .failed
        .map(_ shouldBe ResponseTimeoutException(timeout))
    }

    "complete whenDone" in {
      val processor = IntoableProcessor(Process[Int, Int](), "name")
      val done      = processor.whenDone
      processor.shutdown()
      done.map(_ => succeed)
    }
  }

  "Using an IntoableProcessor locally" should {
    "eventually emit into the outer stream" in {
      val process   = Process[String, Int]().map(_.length)
      val processor = IntoableProcessor(process, "name")
      Source
        .single("abc")
        .into(processor.sink, 1.second)
        .runWith(Sink.head)
        .map(_ shouldBe 3)
    }

    "fail after the given timeout" in {
      val delay     = 100.milliseconds
      val process   = Process[String, String]().delay(delay)
      val processor = IntoableProcessor(process, "name")
      Source
        .single("abc")
        .into(processor.sink, 100.milliseconds)
        .withAttributes(ActorAttributes.supervisionStrategy(Supervision.resumingDecider))
        .runWith(Sink.headOption)
        .map(_ shouldBe None)
    }

    "resume on failure" in {
      val process   = Process[(Int, Int), Int]().map { case (n, m) => n / m }
      val processor = IntoableProcessor(process, "name")
      Source(List((4, 0), (4, 2)))
        .into(processor.sink, 100.milliseconds)
        .withAttributes(ActorAttributes.supervisionStrategy(Supervision.resumingDecider))
        .runWith(Sink.head)
        .map(_ shouldBe 2)
    }

    "at most drop as many requests as the bufferSize on shutdown" in {
      val process   = Process[Int, Int]().throttle(1, 100.milliseconds, 0, ThrottleMode.Shaping)
      val processor = IntoableProcessor(process, "name", 2)
      Source(1.to(10))
        .map { n =>
          if (n == 7) processor.shutdown()
          n
        }
        .into(processor.sink, 1.seconds)
        .withAttributes(ActorAttributes.supervisionStrategy(Supervision.resumingDecider))
        .runWith(Sink.seq)
        .map(_.size should be >= 5)
    }
  }
}
