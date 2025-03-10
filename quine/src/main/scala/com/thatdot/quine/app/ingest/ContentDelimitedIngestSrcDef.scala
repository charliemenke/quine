package com.thatdot.quine.app.ingest

import scala.util.Success

import akka.NotUsed
import akka.stream.alpakka.csv.scaladsl.{CsvParsing, CsvToMap}
import akka.stream.contrib.SwitchMode
import akka.stream.scaladsl.{Flow, Framing, Source}
import akka.util.ByteString

import com.thatdot.quine.app.ingest.serialization.{
  CypherImportFormat,
  CypherJsonInputFormat,
  CypherRawInputFormat,
  CypherStringInputFormat
}
import com.thatdot.quine.graph.cypher.Value
import com.thatdot.quine.graph.{CypherOpsGraph, cypher}
import com.thatdot.quine.routes.FileIngestFormat
import com.thatdot.quine.routes.FileIngestFormat.{CypherCsv, CypherJson, CypherLine}

/** Ingest source runtime that requires managing its own record delimitation -- for example, line-based ingests or CSV
  */
abstract class ContentDelimitedIngestSrcDef(
  initialSwitchMode: SwitchMode,
  format: CypherImportFormat,
  src: Source[ByteString, NotUsed],
  encodingString: String,
  parallelism: Int,
  startAtOffset: Long,
  ingestLimit: Option[Long],
  maxPerSecond: Option[Int],
  name: String
)(implicit graph: CypherOpsGraph)
    extends RawValuesIngestSrcDef(format, initialSwitchMode, parallelism, maxPerSecond, Seq(), name) {

  val (charset, transcode) = IngestSrcDef.getTranscoder(encodingString)

  def bounded[A]: Flow[A, A, NotUsed] = ingestLimit match {
    case None => Flow[A].drop(startAtOffset)
    case Some(limit) => Flow[A].drop(startAtOffset).take(limit)
  }
}

/** Ingest source runtime that delimits its records by newline characters in the input stream
  */
abstract class LineDelimitedIngestSrcDef(
  initialSwitchMode: SwitchMode,
  format: CypherImportFormat,
  src: Source[ByteString, NotUsed],
  encodingString: String,
  parallelism: Int,
  maximumLineSize: Int,
  startAtOffset: Long,
  ingestLimit: Option[Long],
  maxPerSecond: Option[Int],
  name: String
)(implicit graph: CypherOpsGraph)
    extends ContentDelimitedIngestSrcDef(
      initialSwitchMode,
      format,
      src,
      encodingString,
      parallelism,
      startAtOffset,
      ingestLimit,
      maxPerSecond,
      name
    ) {

  type InputType = ByteString

  val newLineDelimited: Flow[ByteString, ByteString, NotUsed] = Framing
    .delimiter(ByteString("\n"), maximumLineSize, allowTruncation = true)
    .map(line => if (!line.isEmpty && line.last == '\r') line.dropRight(1) else line)

  def rawBytes(value: ByteString): Array[Byte] = value.toArray
}

case class CsvIngestSrcDef(
  initialSwitchMode: SwitchMode,
  format: FileIngestFormat.CypherCsv,
  src: Source[ByteString, NotUsed],
  encodingString: String,
  parallelism: Int,
  maximumLineSize: Int,
  startAtOffset: Long,
  ingestLimit: Option[Long],
  maxPerSecond: Option[Int],
  override val name: String
)(implicit graph: CypherOpsGraph)
    extends ContentDelimitedIngestSrcDef(
      initialSwitchMode,
      new CypherRawInputFormat(format.query, format.parameter),
      src,
      encodingString,
      parallelism,
      startAtOffset,
      ingestLimit,
      maxPerSecond,
      name
    ) {

  type InputType = List[ByteString] // csv row

  def source(): Source[List[ByteString], NotUsed] = src
    .via(
      CsvParsing.lineScanner(format.delimiter.byte, format.quoteChar.byte, format.escapeChar.byte, maximumLineSize)
    )
    .via(bounded)

  def csvHeadersFlow(headerDef: Either[Boolean, List[String]]): Flow[List[ByteString], Value, NotUsed] =
    headerDef match {
      case Right(h) =>
        CsvToMap
          .withHeaders(h: _*)
          .map(m => cypher.Expr.Map(m.mapValues(bs => cypher.Expr.Str(bs.decodeString(charset)))))
      case Left(true) =>
        CsvToMap
          .toMap()
          .map(m => cypher.Expr.Map(m.mapValues(bs => cypher.Expr.Str(bs.decodeString(charset)))))
      case Left(false) =>
        Flow[List[ByteString]]
          .map(l => cypher.Expr.List(l.map(bs => cypher.Expr.Str(bs.decodeString(charset))).toVector))
    }

  override val deserializeAndMeter: Flow[List[ByteString], TryDeserialized, NotUsed] =
    Flow[List[ByteString]]
      // NB when using headers, the record count here will consider the header-defining row as a "record". Since Quine
      // metrics are only heuristic, this is an acceptable trade-off for simpler code.
      .wireTap(bs => meter.mark(bs.map(_.length).sum))
      .via(csvHeadersFlow(format.headers))
      // Here the empty list is a placeholder for the original
      // value in the TryDeserialized response value. Since this
      // is only used in errors and this is a success response,
      // it's not necessary to populate it.
      .map((t: Value) => (Success(t), Nil))

  /** Define a way to extract raw bytes from a single input event */
  def rawBytes(value: List[ByteString]): Array[Byte] = {
    // inefficient, but should never be used anyways since csv defines its own deserializeAndMeter
    logger.debug(
      s"${getClass.getSimpleName}.rawBytes was called: this function has an inefficient implementation but should not" +
      s"be accessible during normal operation."
    )
    value.reduce { (l, r) =>
      val bs = ByteString.createBuilder
      bs ++= l
      bs += format.delimiter.byte
      bs ++= r
      bs.result()
    }.toArray
  }
}

case class StringIngestSrcDef(
  initialSwitchMode: SwitchMode,
  format: CypherStringInputFormat,
  src: Source[ByteString, NotUsed],
  encodingString: String,
  parallelism: Int,
  maximumLineSize: Int,
  startAtOffset: Long,
  ingestLimit: Option[Long],
  maxPerSecond: Option[Int],
  override val name: String
)(implicit graph: CypherOpsGraph)
    extends LineDelimitedIngestSrcDef(
      initialSwitchMode,
      format,
      src,
      encodingString,
      parallelism,
      maximumLineSize,
      startAtOffset,
      ingestLimit,
      maxPerSecond,
      name
    ) {

  def source(): Source[ByteString, NotUsed] = src
    .via(transcode)
    .via(newLineDelimited)
    .via(bounded)

}

case class JsonLinesIngestSrcDef(
  initialSwitchMode: SwitchMode,
  format: CypherJsonInputFormat,
  src: Source[ByteString, NotUsed],
  encodingString: String,
  parallelism: Int,
  maximumLineSize: Int,
  startAtOffset: Long,
  ingestLimit: Option[Long],
  maxPerSecond: Option[Int],
  override val name: String
)(implicit graph: CypherOpsGraph)
    extends LineDelimitedIngestSrcDef(
      initialSwitchMode,
      format,
      src,
      encodingString,
      parallelism,
      maximumLineSize,
      startAtOffset,
      ingestLimit,
      maxPerSecond,
      name
    ) {

  def source(): Source[ByteString, NotUsed] = src
    .via(transcode)
    .via(newLineDelimited)
    .via(bounded)

  override def rawBytes(value: ByteString): Array[Byte] = value.toArray

}

object ContentDelimitedIngestSrcDef {

  def apply(
    initialSwitchMode: SwitchMode,
    format: FileIngestFormat,
    src: Source[ByteString, NotUsed],
    encodingString: String,
    parallelism: Int,
    maximumLineSize: Int,
    startAtOffset: Long,
    ingestLimit: Option[Long],
    maxPerSecond: Option[Int],
    name: String
  )(implicit graph: CypherOpsGraph): ContentDelimitedIngestSrcDef =
    format match {
      case CypherLine(query, parameter) =>
        StringIngestSrcDef(
          initialSwitchMode,
          new CypherStringInputFormat(query, parameter, encodingString),
          src,
          encodingString,
          parallelism,
          maximumLineSize,
          startAtOffset,
          ingestLimit,
          maxPerSecond,
          name
        )
      case CypherJson(query, parameter) =>
        JsonLinesIngestSrcDef(
          initialSwitchMode,
          new CypherJsonInputFormat(query, parameter),
          src,
          encodingString,
          parallelism,
          maximumLineSize,
          startAtOffset,
          ingestLimit,
          maxPerSecond,
          name
        )

      case cv @ CypherCsv(_, _, _, _, _, _) =>
        CsvIngestSrcDef(
          initialSwitchMode,
          cv,
          src,
          encodingString,
          parallelism,
          maximumLineSize,
          startAtOffset,
          ingestLimit,
          maxPerSecond,
          name
        )
    }

}
