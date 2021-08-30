// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.apiserver.services

import java.util.UUID

import com.daml.ledger.api.v1.command_service.CommandServiceGrpc.CommandService
import com.daml.ledger.api.v1.command_service.{CommandServiceGrpc, SubmitAndWaitRequest}
import com.daml.ledger.api.v1.commands.{Command, Commands, CreateCommand}
import com.daml.ledger.client.services.commands.CommandSubmission
import com.daml.ledger.client.services.commands.tracker.CompletionResponse.CompletionSuccess
import com.daml.ledger.resources.{ResourceContext, ResourceOwner}
import com.daml.logging.LoggingContext
import com.daml.platform.apiserver.services.ApiCommandServiceSpec._
import com.daml.platform.apiserver.services.tracking.Tracker
import com.google.rpc.status.{Status => StatusProto}
import io.grpc.Status
import io.grpc.inprocess.{InProcessChannelBuilder, InProcessServerBuilder}
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

class ApiCommandServiceSpec
    extends AsyncWordSpec
    with Matchers
    with MockitoSugar
    with ArgumentMatchersSugar {
  private implicit val resourceContext: ResourceContext = ResourceContext(executionContext)
  private implicit val loggingContext: LoggingContext = LoggingContext.ForTesting

  "the command service" should {
    "submit a request, and wait for a response" in {
      val commands = Commands(
        ledgerId = "ledger ID",
        commandId = "command ID",
        commands = Seq(
          Command.of(Command.Command.Create(CreateCommand()))
        ),
      )
      val submissionTracker = mock[Tracker]
      when(
        submissionTracker.track(any[CommandSubmission])(any[ExecutionContext], any[LoggingContext])
      ).thenReturn(
        Future.successful(Right(CompletionSuccess("command ID", "transaction ID", OkStatus)))
      )
      openChannel(new ApiCommandService(UnimplementedTransactionServices, submissionTracker)).use {
        stub =>
          val request = SubmitAndWaitRequest.of(Some(commands))
          stub.submitAndWaitForTransactionId(request).map { response =>
            response.transactionId should be("transaction ID")
            verify(submissionTracker).track(
              eqTo(CommandSubmission(commands))
            )(any[ExecutionContext], any[LoggingContext])
            succeed
          }
      }
    }

    "close the supplied tracker when closed" in {
      val submissionTracker = mock[Tracker]
      val service = new ApiCommandService(UnimplementedTransactionServices, submissionTracker)

      verifyZeroInteractions(submissionTracker)

      service.close()
      verify(submissionTracker).close()
      succeed
    }
  }
}

object ApiCommandServiceSpec {
  private val UnimplementedTransactionServices = new ApiCommandService.TransactionServices(
    getTransactionById = _ => Future.failed(new RuntimeException("This should never be called.")),
    getFlatTransactionById = _ =>
      Future.failed(new RuntimeException("This should never be called.")),
  )

  private val OkStatus = StatusProto.of(Status.Code.OK.value, "", Seq.empty)

  private def openChannel(
      service: ApiCommandService
  ): ResourceOwner[CommandServiceGrpc.CommandServiceStub] =
    for {
      name <- ResourceOwner.forValue(() => UUID.randomUUID().toString)
      _ <- ResourceOwner.forServer(
        InProcessServerBuilder
          .forName(name)
          .addService(() => CommandService.bindService(service, ExecutionContext.global)),
        shutdownTimeout = 10.seconds,
      )
      channel <- ResourceOwner.forChannel(
        InProcessChannelBuilder.forName(name),
        shutdownTimeout = 10.seconds,
      )
    } yield CommandServiceGrpc.stub(channel)
}