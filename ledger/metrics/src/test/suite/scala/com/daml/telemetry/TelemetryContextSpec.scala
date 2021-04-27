// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.telemetry

import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Assertion, BeforeAndAfterEach}

/** Other cases are covered by [[TelemetrySpec]] */
class TelemetryContextSpec
    extends TelemetrySpecBase
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfterEach {

  override protected def afterEach(): Unit = spanExporter.reset()

  "DefaultTelemetryContext.runInOpenTelemetryScope" should {
    "run a body and create a current context with a span" in {
      val tracer = tracerProvider.get(anInstrumentationName)
      val span = tracer
        .spanBuilder(aSpanName)
        .setAttribute(
          anApplicationIdSpanAttribute._1.key,
          anApplicationIdSpanAttribute._2,
        )
        .startSpan()

      runInOpenTelemetryScopeAndAssert(DefaultTelemetryContext(tracer, span))

      val attributes = spanExporter.finishedSpanAttributes
      attributes should contain(anApplicationIdSpanAttribute)
    }
  }

  "RootDefaultTelemetryContext.runInOpenTelemetryScope" should {
    "run a body" in {
      val tracer = tracerProvider.get(anInstrumentationName)
      runInOpenTelemetryScopeAndAssert(RootDefaultTelemetryContext(tracer))
    }
  }

  "NoOpTelemetryContext.runInOpenTelemetryScope" should {
    "run a body" in {
      runInOpenTelemetryScopeAndAssert(NoOpTelemetryContext)
    }
  }

  private def runInOpenTelemetryScopeAndAssert(telemetryContext: TelemetryContext): Assertion = {
    var placeholder: Option[_] = None
    telemetryContext.runInOpenTelemetryScope {
      Span
        .fromContext(Context.current())
        .end() // end the span from the current context to be able to make assertions on its attributes
      placeholder = Some(())
    }
    placeholder shouldBe Some(())
  }
}
