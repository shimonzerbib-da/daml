// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.timestamps

import com.daml.timestamps.JavaScalaProtobuf._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import java.time.{Instant => JavaTimestamp}

final class JavaScalaProtobufTimestampConversionsSpec
    extends AnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks {
  "converting a Java instant to a Scala Protocol Buffers timestamp" should {
    "convert to and fro" in {
      forAll { (timestamp: JavaTimestamp) =>
        timestamp.asScalaProto.asJava should be(timestamp)
      }
    }
  }
}
