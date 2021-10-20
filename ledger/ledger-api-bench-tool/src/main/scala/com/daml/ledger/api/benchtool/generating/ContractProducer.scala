// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.api.benchtool.generating

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.daml.ledger.api.benchtool.infrastructure.TestDars
import com.daml.ledger.api.benchtool.services.LedgerApiServices
import com.daml.ledger.api.benchtool.util.SimpleFileReader
import com.daml.ledger.api.v1.commands.Commands
import com.daml.ledger.client.binding.Primitive
import com.daml.ledger.client.binding.Primitive.Party
import com.daml.ledger.resources.{ResourceContext, ResourceOwner}
import com.daml.ledger.test.model.Foo.Foo1
import org.slf4j.LoggerFactory
import scalaz.syntax.tag._

import java.io.File
import java.nio.charset.StandardCharsets
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Random, Success, Try}

case class ContractProducer(services: LedgerApiServices) {
  private val logger = LoggerFactory.getLogger(getClass)

  private val identifierSuffix = f"${System.nanoTime}%x"
  private val applicationId = "benchtool"
  private val workflowId = s"$applicationId-$identifierSuffix"
  private def commandId(index: Int) = s"command-$index-$identifierSuffix"

  def create(
      descriptorFile: File
  )(implicit ec: ExecutionContext): Future[Unit] = {
    logger.info("Generating contracts...")
    for {
      descriptor <- Future.fromTry(parseDescriptor(descriptorFile))
      party <- allocateParty()
      _ <- uploadTestDars()
      _ <- createContracts(descriptor = descriptor, party = party)
    } yield {
      logger.info("Contracts produced successfully.")
    }

  }

  private def parseDescriptor(descriptorFile: File): Try[ContractSetDescriptor] = {
    SimpleFileReader.readFile(descriptorFile)(DescriptorParser.parse).flatMap {
      case Left(err: DescriptorParser.DescriptorParserError) =>
        val message = s"Descriptor parsing error. Details: ${err.details}"
        logger.error(message)
        Failure(new RuntimeException(message))
      case Right(descriptor) =>
        logger.info(s"Descriptor parsed: $descriptor")
        Success(descriptor)
    }
  }

  private def allocateParty()(implicit ec: ExecutionContext): Future[Primitive.Party] = {
    val party0Hint = s"party-0-$identifierSuffix"
    services.partyManagementService.allocateParty(party0Hint)
  }

  private def uploadTestDars()(implicit ec: ExecutionContext): Future[Unit] = {
    def uploadDar(dar: TestDars.DarFile, submissionId: String): Future[Unit] = {
      logger.info(s"Uploading dar: ${dar.name}")
      services.packageManagementService.uploadDar(
        bytes = dar.bytes,
        submissionId = submissionId,
      )
    }

    for {
      dars <- Future.fromTry(TestDars.readAll())
      _ <- Future.sequence(dars.zipWithIndex.map { case (dar, index) =>
        uploadDar(dar, s"submission-dars-$index-$identifierSuffix")
      })
    } yield ()
  }

  private def createContract(index: Int, party: Party, payloadSizeBytes: Int, random: Random)(
      implicit ec: ExecutionContext
  ): Future[Unit] = {
    // TODO: move to a separate class
    val randomPayload = new String(random.nextBytes(payloadSizeBytes), StandardCharsets.UTF_8)
    val createCommand = Foo1(
      signatory = party,
      observers = List(party),
      payload = randomPayload,
    ).create.command
    val commands = new Commands(
      ledgerId = services.ledgerId,
      applicationId = applicationId,
      commandId = commandId(index),
      party = party.unwrap,
      commands = List(createCommand),
      workflowId = workflowId,
    )
    services.commandService.submitAndWait(commands).map(_ => ())
  }

  private def createContracts(descriptor: ContractSetDescriptor, party: Party)(implicit
      ec: ExecutionContext
  ): Future[Unit] = {
    implicit val resourceContext: ResourceContext = ResourceContext(ec)
    val random = new Random(System.currentTimeMillis())
    materializerOwner()
      .use { implicit materializer =>
        Source
          .fromIterator(() => (1 to descriptor.numberOfInstances).iterator)
          .throttle(
            elements = 100,
            per = 1.second,
          )
          .mapAsync(4)(index => createContract(index, party, descriptor.payloadSizeBytes, random))
          .run()
      }
      .map(_ => ())
  }

  private def materializerOwner(): ResourceOwner[Materializer] = {
    for {
      actorSystem <- ResourceOwner.forActorSystem(() => ActorSystem("CommandSubmissionSystem"))
      materializer <- ResourceOwner.forMaterializer(() => Materializer(actorSystem))
    } yield materializer
  }
}
