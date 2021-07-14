// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf.archive

import com.daml.lf.data.Bytes
import com.daml.lf.data.TryOps.sequence

import java.io.{File, FileInputStream, IOException}
import java.util.zip.ZipInputStream
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try, Using}

sealed abstract class GenDarReader[A] {
  import GenDarReader._

  def readArchiveFromFile(
      darFile: File,
      entrySizeThreshold: Int = EntrySizeThreshold,
  ): Try[Dar[A]]

  def readArchive(
      name: String,
      darStream: ZipInputStream,
      entrySizeThreshold: Int = EntrySizeThreshold,
  ): Try[Dar[A]]
}

private[archive] final class GenDarReaderImpl[A](reader: GenReader[A]) extends GenDarReader[A] {

  import GenDarReader._

  /** Reads an archive from a File. */
  override def readArchiveFromFile(
      darFile: File,
      entrySizeThreshold: Int = EntrySizeThreshold,
  ): Try[Dar[A]] =
    Using(new ZipInputStream(new FileInputStream(darFile)))(
      readArchive(darFile.getName, _, entrySizeThreshold)
    ).flatten

  /** Reads an archive from a ZipInputStream. The stream will be closed by this function! */
  override def readArchive(
      name: String,
      darStream: ZipInputStream,
      entrySizeThreshold: Int = EntrySizeThreshold,
  ): Try[Dar[A]] =
    for {
      entries <- loadZipEntries(name, darStream, entrySizeThreshold)
      names <- entries.readDalfNames
      main <- parseOne(entries.get)(names.main)
      deps <- parseAll(entries.get)(names.dependencies)
    } yield Dar(main, deps)

  // Fails if a zip bomb is detected
  @throws[Error.ZipBomb]
  @throws[IOException]
  private[this] def slurpWithCaution(
      name: String,
      zip: ZipInputStream,
      entrySizeThreshold: Int,
  ): (String, Bytes) = {
    val buffSize = 4 * 1024 // 4k
    val buffer = Array.ofDim[Byte](buffSize)
    var output = Bytes.Empty
    Iterator.continually(zip.read(buffer)).takeWhile(_ >= 0).foreach { size =>
      output ++= Bytes.fromByteArray(buffer, 0, size)
      if (output.length >= entrySizeThreshold) throw Error.ZipBomb()
    }
    name -> output
  }

  private[this] def loadZipEntries(
      name: String,
      darStream: ZipInputStream,
      entrySizeThreshold: Int,
  ): Try[ZipEntries] =
    Try(
      Iterator
        .continually(darStream.getNextEntry)
        .takeWhile(_ != null)
        .map(entry => slurpWithCaution(entry.getName, darStream, entrySizeThreshold))
        .toMap
    ).map(ZipEntries(name, _))

  private[this] def parseAll(getPayload: String => Try[Bytes])(names: List[String]): Try[List[A]] =
    sequence(names.map(parseOne(getPayload)))

  private[this] def parseOne(getPayload: String => Try[Bytes])(s: String): Try[A] =
    getPayload(s).flatMap(bytes => Try(reader.fromBytes(bytes)))

}

object GenDarReader {

  def apply[A](reader: GenReader[A]): GenDarReader[A] = new GenDarReaderImpl[A](reader)

  private val ManifestName = "META-INF/MANIFEST.MF"
  private[archive] val EntrySizeThreshold = 1024 * 1024 * 1024 // 1 GB

  private[archive] case class ZipEntries(name: String, entries: Map[String, Bytes]) {
    private[archive] def get(entryName: String): Try[Bytes] = {
      entries.get(entryName) match {
        case Some(is) => Success(is)
        case None => Failure(Error.InvalidZipEntry(entryName, this))
      }
    }

    private[archive] def readDalfNames: Try[Dar[String]] =
      get(ManifestName)
        .flatMap(DarManifestReader.dalfNames)
        .recoverWith { case NonFatal(e1) => Failure(Error.InvalidDar(this, e1)) }
  }
}
