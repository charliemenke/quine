package com.thatdot.quine.app

import java.nio.file.{Paths, StandardOpenOption}

import scala.concurrent.duration._
import scala.util.{Failure, Random, Success, Try}

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.{HttpEntity, HttpMethods, HttpRequest}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.kafka.scaladsl.{Producer => KafkaProducer}
import akka.kafka.{ProducerMessage, ProducerSettings}
import akka.stream.alpakka.kinesis.KinesisFlowSettings
import akka.stream.alpakka.kinesis.scaladsl.KinesisFlow
import akka.stream.alpakka.sns.scaladsl.SnsPublisher
import akka.stream.scaladsl.{FileIO, Flow, Keep}
import akka.util.ByteString

import cats.syntax.either._
import com.typesafe.scalalogging.{LazyLogging, Logger}
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArraySerializer
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequestEntry
import software.amazon.awssdk.services.sns.SnsAsyncClient
import ujson.BytesRenderer

import com.thatdot.quine.app.ingest.util.AwsOps
import com.thatdot.quine.app.ingest.util.AwsOps.AwsBuilderOps
import com.thatdot.quine.app.serialization.QuineValueToProtobuf
import com.thatdot.quine.app.util.AtLeastOnceCypherQuery
import com.thatdot.quine.compiler
import com.thatdot.quine.graph.MasterStream.SqResultsExecToken
import com.thatdot.quine.graph.cypher.QueryContext
import com.thatdot.quine.graph.messaging.StandingQueryMessage.ResultId
import com.thatdot.quine.graph.{BaseGraph, CypherOpsGraph, StandingQueryResult, cypher}
import com.thatdot.quine.model.{QuineIdProvider, QuineValue}
import com.thatdot.quine.routes.{OutputFormat, StandingQueryResultOutputUserDef}
import com.thatdot.quine.util.StringInput.filenameOrUrl

object StandingQueryResultOutput extends LazyLogging {

  import StandingQueryResultOutputUserDef._

  // Invariant: these keys must be fixed to the names of the loggers in Quine App's application.conf
  private val printLogger = Logger("thatdot.StandingQueryResults")
  private val printLoggerNonBlocking = Logger("thatdot.StandingQueryResultsSampled")

  /** Construct a destination to which results are output
    *
    * @param name name of the Standing Query Output
    * @param output configuration for handling the results
    * @param graph reference to the graph
    */
  def resultHandlingFlow(
    name: String,
    output: StandingQueryResultOutputUserDef,
    graph: CypherOpsGraph
  ): Flow[StandingQueryResult, SqResultsExecToken, NotUsed] = {
    val execToken = SqResultsExecToken(s"SQ: $name")
    output match {
      case Drop => Flow[StandingQueryResult].map(_ => execToken)
      case PostToEndpoint(url, parallelism, onlyPositiveMatchData) =>
        // TODO: use a host connection pool

        implicit val system: ActorSystem = graph.system
        implicit val idProvider: QuineIdProvider = graph.idProvider
        val http = Http()

        Flow[StandingQueryResult]
          .mapAsync(parallelism) { (result: StandingQueryResult) =>
            val request = HttpRequest(
              method = HttpMethods.POST,
              uri = url,
              entity = HttpEntity(
                contentType = `application/json`,
                ujson.write(
                  if (onlyPositiveMatchData) QuineValue.toJson(QuineValue.Map(result.data)) else result.toJson
                )
              )
            )

            val posted =
              http
                .singleRequest(request)
                .flatMap(response =>
                  if (response.status.isSuccess()) {
                    response.entity
                      .discardBytes()
                      .future()
                  } else {
                    Unmarshal(response)
                      .to[String]
                      .andThen {
                        case Failure(err) =>
                          logger.error(
                            s"Failed to deserialize error response from POST $result to $url. " +
                            s"Response status was ${response.status}",
                            err
                          )
                        case Success(responseBody) =>
                          logger.error(
                            s"Failed to POST $result to $url. " +
                            s"Response was ${response.status} (body: $responseBody)"
                          )
                      }(system.dispatcher)
                  }
                )(system.dispatcher)
                .map(_ => execToken)(system.dispatcher)

            // TODO: principled error handling
            posted.recover { case err =>
              logger.error("Failed to POST standing query result", err)
              execToken
            }(system.dispatcher)
          }

      case WriteToKafka(topic, bootstrapServers, format) =>
        val settings = ProducerSettings(
          graph.system,
          new ByteArraySerializer,
          new ByteArraySerializer
        ).withBootstrapServers(bootstrapServers)

        serialized(name, format, graph)
          .map(bytes => ProducerMessage.single(new ProducerRecord[Array[Byte], Array[Byte]](topic, bytes)))
          .via(KafkaProducer.flexiFlow(settings).named(s"sq-output-kafka-producer-for-$name"))
          .map(_ => execToken)

      case WriteToKinesis(
            credentials,
            streamName,
            format,
            kinesisParallelism,
            kinesisMaxBatchSize,
            kinesisMaxRecordsPerSecond,
            kinesisMaxBytesPerSecond
          ) =>
        val builder = KinesisAsyncClient
          .builder()
          .credentials(credentials)
          .httpClient(NettyNioAsyncHttpClient.builder.maxConcurrency(AwsOps.httpConcurrencyPerClient).build())
        val kinesisAsyncClient: KinesisAsyncClient =
          builder
            .build()
        graph.system.registerOnTermination(kinesisAsyncClient.close()) // TODO

        val settings = {
          var s = KinesisFlowSettings.create()
          s = kinesisParallelism.foldLeft(s)(_ withParallelism _)
          s = kinesisMaxBatchSize.foldLeft(s)(_ withMaxBatchSize _)
          s = kinesisMaxRecordsPerSecond.foldLeft(s)(_ withMaxRecordsPerSecond _)
          s = kinesisMaxBytesPerSecond.foldLeft(s)(_ withMaxBytesPerSecond _)
          s
        }

        serialized(name, format, graph)
          .map { bytes =>
            val builder = PutRecordsRequestEntry.builder()
            builder.data(SdkBytes.fromByteArray(bytes))
            builder.partitionKey("undefined")
            builder.explicitHashKey(BigInt(128, Random).toString)
            builder.build()
          }
          .via(
            KinesisFlow(
              streamName,
              settings
            )(kinesisAsyncClient).named(s"sq-output-kinesis-producer-for-$name")
          )
          .map(_ => execToken)

      case WriteToSNS(credentials, topic) =>
        val awsSnsClient = SnsAsyncClient
          .builder()
          .credentials(credentials)
          .httpClient(
            NettyNioAsyncHttpClient.builder.maxConcurrency(AwsOps.httpConcurrencyPerClient).build()
          )
          .build()

        // NOTE alpakka requires we close the SNS client
        graph.system.registerOnTermination(awsSnsClient.close()) // TODO

        // NB: by default, this will make 10 parallel requests [configurable via parameter to SnsPublisher.flow]
        // TODO: FIXME if any request to SNS errors, that thread (of the aforementioned 10) will retry its request
        // indefinitely. If all worker threads block, the SnsPublisher.flow will backpressure indefinitely.
        Flow[StandingQueryResult]
          .map(result => result.toJson(graph.idProvider).toString + "\n")
          .viaMat(SnsPublisher.flow(topic)(awsSnsClient).named(s"sq-output-sns-producer-for-$name"))(Keep.right)
          .map(_ => execToken)

      case PrintToStandardOut(logLevel, logMode) =>
        import PrintToStandardOut._
        val resultLogger: Logger = logMode match {
          case LogMode.Complete => printLogger
          case LogMode.FastSampling => printLoggerNonBlocking
        }
        val logFn: (String => Unit) =
          logLevel match {
            case LogLevel.Trace => resultLogger.trace(_)
            case LogLevel.Debug => resultLogger.debug(_)
            case LogLevel.Info => resultLogger.info(_)
            case LogLevel.Warn => resultLogger.warn(_)
            case LogLevel.Error => resultLogger.error(_)
          }

        Flow[StandingQueryResult].map { result =>
          logFn(s"Standing query `$name` match: ${result.toJson(graph.idProvider)}")
          execToken
        }

      case WriteToFile(path) =>
        Flow[StandingQueryResult]
          .map(result => ByteString(result.toJson(graph.idProvider).toString + "\n"))
          .alsoTo(
            FileIO
              .toPath(
                Paths.get(path),
                Set(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
              )
              .named(s"sq-output-file-writer-for-$name")
          )
          .map(_ => execToken)

      case PostToSlack(hookUrl, onlyPositiveMatchData, intervalSeconds) =>
        implicit val system: ActorSystem = graph.system
        implicit val idProvider: QuineIdProvider = graph.idProvider
        val http = Http()

        // how often to send notifications (notifications will be batched by [[PostToSlack.SlackSerializable.apply]]
        val rate = math.max(1, intervalSeconds).seconds

        Flow[StandingQueryResult]
          .conflateWithSeed(List(_))((acc, newResult) => newResult :: acc)
          .throttle(1, rate) // Slack webhooks have a 1 message per second rate limit
          .map(newResults => SlackSerializable(onlyPositiveMatchData, newResults))
          .collect { case Some(slackMessage) => slackMessage }
          .mapAsync(1) { result =>
            val request = HttpRequest(
              method = HttpMethods.POST,
              uri = hookUrl,
              entity = HttpEntity.apply(contentType = `application/json`, result.slackJson)
            )
            val posted = http
              .singleRequest(request)
              .flatMap { response =>
                if (response.status.isSuccess()) {
                  response.entity
                    .discardBytes()
                    .future()
                } else {
                  Unmarshal(response)
                    .to[String]
                    .andThen {
                      case Failure(err) =>
                        logger.error(
                          s"Failed to deserialize error response from POST ${result.slackJson} to slack webhook. " +
                          s"Response status was ${response.status}",
                          err
                        )
                      case Success(responseBody) =>
                        logger.error(
                          s"Failed to POST ${result.slackJson} to slack webhook. " +
                          s"Response status was ${response.status}. Body was $responseBody"
                        )
                    }(system.dispatcher)
                }
              }(system.dispatcher)
              .map(_ => execToken)(system.dispatcher)

            // TODO: principled error handling
            posted.recover { case err =>
              logger.error("Failed to POST standing query result", err)
              execToken
            }(system.dispatcher)
          }

      case CypherQuery(query, parameter, parallelism, andThen, allowAllNodeScan, shouldRetry) =>
        val compiledQuery @ cypher.CompiledQuery(_, queryAst, _, _, _) = compiler.cypher.compile(
          query,
          unfixedParameters = Seq(parameter)
        )

        // TODO: When in the initial set of SQ outputs, these should be tested before the SQ is registered!
        if (queryAst.canContainAllNodeScan && !allowAllNodeScan) {
          throw new RuntimeException(
            "Cypher query may contain full node scan; re-write without possible full node scan, or pass allowAllNodeScan true. " +
            s"The provided query was: $query"
          )
        }
        if (!queryAst.isIdempotent && shouldRetry) {
          logger.warn(
            """Could not verify that the provided Cypher query is idempotent. If timeouts or external system errors
              |occur, query execution may be retried and duplicate data may be created. To avoid this,
              |set shouldRetry = false in the Standing Query output""".stripMargin.replace('\n', ' ')
          )
        }

        val andThenFlow: Flow[(StandingQueryResult.Meta, cypher.QueryContext), SqResultsExecToken, NotUsed] =
          (andThen match {
            case None =>
              Flow[(StandingQueryResult.Meta, cypher.QueryContext)]
                .statefulMapConcat { () =>
                  var warned = false

                  tup => {
                    logger.whenWarnEnabled {
                      if (!warned) {
                        warned = true
                        logger.warn(
                          s"""Unused Cypher Standing Query output for Standing Query output: $name with:
                             |${tup._2.environment.size} columns. Did you mean to specify `andThen`?""".stripMargin
                            .replace('\n', ' ')
                        )
                      }
                    }
                    List(execToken)
                  }
                }

            case Some(thenOutput) =>
              Flow[(StandingQueryResult.Meta, cypher.QueryContext)]
                .map { case (meta: StandingQueryResult.Meta, qc: cypher.QueryContext) =>
                  val newData = qc.environment.map { case (keySym, cypherVal) =>
                    keySym.name -> Try(cypher.Expr.toQuineValue(cypherVal)).getOrElse {
                      logger.warn(
                        s"""Cypher Standing Query output: $name included cypher value not representable as a
                           |Quine value (logged at INFO level). Using `null` instead.""".stripMargin.replace('\n', ' ')
                      )
                      logger.info(
                        s"""Cypher Value: $cypherVal could not be represented as a Quine value in Standing
                           |Query output: $name""".stripMargin.replace('\n', ' ')
                      )
                      QuineValue.Null
                    }
                  }
                  StandingQueryResult(meta, newData)
                }
                .via(resultHandlingFlow(name, thenOutput, graph))
          }).named(s"sq-output-andthen-for-$name")

        lazy val atLeastOnceCypherQuery =
          AtLeastOnceCypherQuery(compiledQuery, parameter, s"sq-output-action-query-for-$name")

        Flow[StandingQueryResult]
          .flatMapMerge(
            breadth = parallelism,
            result => {
              val value: cypher.Value = cypher.Expr.fromQuineValue(result.toQuineValueMap)

              val cypherResultRows =
                if (shouldRetry) atLeastOnceCypherQuery.stream(value)(graph)
                else compiledQuery.run(Map(parameter -> value))(graph).results

              cypherResultRows
                .map { resultRow =>
                  QueryContext(compiledQuery.columns.zip(resultRow).toMap)
                }
                .map(data => (result.meta, data))
            }
          )
          .via(andThenFlow)
    }
  }.named(s"sq-output-${name}")

  private def serialized(
    name: String,
    format: OutputFormat,
    graph: BaseGraph
  ): Flow[StandingQueryResult, Array[Byte], NotUsed] =
    format match {
      case OutputFormat.JSON =>
        Flow[StandingQueryResult].map(_.toJson(graph.idProvider).transform(BytesRenderer()).toByteArray)
      case OutputFormat.Protobuf(schemaUrl, typeName) =>
        val serializer = new QuineValueToProtobuf(filenameOrUrl(schemaUrl), typeName)
        Flow[StandingQueryResult]
          .filter(_.meta.isPositiveMatch)
          .map(result =>
            serializer
              .toProtobufBytes(result.data)
              .leftMap { err =>
                logger.warn(
                  s"""On Standing Query output: $name, can't serialize provided datum (logged at INFO level)
                     |to protobuf type: $typeName. Skipping datum.""".stripMargin.replace('\n', ' ')
                )
                logger.info(
                  s"Standing Query output: $name failed to serialize Standing Query result as Protobuf: $result",
                  err
                )
              }
          )
          .collect { case Right(value) => value }
    }

  sealed abstract class SlackSerializable {
    def slackJson: String
  }
  object SlackSerializable {
    def apply(positiveOnly: Boolean, results: Seq[StandingQueryResult])(implicit
      idProvider: QuineIdProvider
    ): Option[SlackSerializable] = results match {
      case Seq() => None // no new results or cancellations
      case cancellations if positiveOnly && !cancellations.exists(_.meta.isPositiveMatch) =>
        None // no new results, only cancellations, and we're configured to drop cancellations
      case Seq(result) => // one new result or cancellations
        if (result.meta.isPositiveMatch) Some(NewResult(result.meta.resultId, result.data))
        else if (!positiveOnly) Some(CancelledResult(result.meta.resultId))
        else None
      case _ => // multiple results (but maybe not all valid given `positiveOnly`)
        val positiveResults = results.filter(_.meta.isPositiveMatch)
        val cancellations = results.filter(!_.meta.isPositiveMatch)

        if (positiveOnly && positiveResults.length == 1) {
          val singleResult = positiveResults.head
          Some(NewResult(singleResult.meta.resultId, singleResult.data))
        } else if (!positiveOnly && positiveResults.isEmpty && cancellations.length == 1) {
          Some(CancelledResult(cancellations.head.meta.resultId))
        } else if (positiveOnly && positiveResults.nonEmpty) {
          Some(MultipleUpdates(positiveResults, Seq.empty))
        } else if (positiveResults.nonEmpty || cancellations.nonEmpty) {
          Some(MultipleUpdates(positiveResults, cancellations.map(_.meta.resultId)))
        } else None
    }
  }

  final case class NewResult(resultId: ResultId, data: Map[String, QuineValue])(implicit idProvider: QuineIdProvider)
      extends SlackSerializable {
    // pretty-printed JSON representing `data`. Note that since this is used as a value in another JSON object, it
    // may not be perfectly escaped (for example, if the data contains a triple-backquote)
    val dataPrettyJson: String = ujson.write(QuineValue.toJson(QuineValue.Map(data)), 2)

    // slack message blocks
    def slackBlocks: Vector[ujson.Obj] = Vector(
      ujson.Obj(
        "type" -> "section",
        "text" -> ujson.Obj(
          "type" -> "mrkdwn",
          "text" -> s"```${dataPrettyJson}```"
        )
      ),
      ujson.Obj(
        "type" -> "context",
        "elements" -> ujson.Arr(
          ujson.Obj(
            "type" -> "mrkdwn",
            "text" -> s"*Result ID:* ${resultId.uuid.toString}"
          )
        )
      )
    )

    override def slackJson: String = ujson
      .Obj(
        "text" -> "New Standing Query Result",
        "blocks" -> ujson.Arr.from(
          ujson.Obj(
            "type" -> "header",
            "text" -> ujson.Obj(
              "type" -> "plain_text",
              "text" -> "New Standing Query Result"
            )
          ) +: slackBlocks
        )
      )
      .render()
  }

  final case class CancelledResult(resultId: ResultId) extends SlackSerializable {
    val slackBlock: Vector[ujson.Obj] = Vector(
      ujson.Obj(
        "type" -> "context",
        "elements" -> ujson.Arr(
          ujson.Obj(
            "type" -> "mrkdwn",
            "text" -> s"*Result ID:* ${resultId.uuid.toString}"
          )
        )
      )
    )
    override def slackJson: String = ujson
      .Obj(
        "text" -> "Standing Query Result Cancelled",
        "blocks" -> ujson.Arr.from(
          ujson.Obj(
            "type" -> "header",
            "text" -> ujson.Obj(
              "type" -> "plain_text",
              "text" -> "Standing Query Result Cancelled"
            )
          ) +: slackBlock
        )
      )
      .render()
  }

  final case class MultipleUpdates(newResults: Seq[StandingQueryResult], newCancellations: Seq[ResultId])(implicit
    idProvider: QuineIdProvider
  ) extends SlackSerializable {
    val newResultsBlocks: Vector[ujson.Obj] = newResults match {
      case Seq() => Vector.empty
      case Seq(result) =>
        ujson.Obj(
          "type" -> "header",
          "text" -> ujson.Obj(
            "type" -> "plain_text",
            "text" -> "New Standing Query Result"
          )
        ) +: NewResult(result.meta.resultId, result.data).slackBlocks
      case result +: remainingResults =>
        val excessResultIds = remainingResults.map(_.meta.resultId.uuid.toString)
        val excessResultItems: Seq[String] =
          if (excessResultIds.length <= 10) excessResultIds
          else excessResultIds.take(9) :+ s"(${excessResultIds.length - 9} more)"

        Vector(
          ujson.Obj(
            "type" -> "header",
            "text" -> ujson.Obj(
              "type" -> "plain_text",
              "text" -> "New Standing Query Results"
            )
          ),
          ujson.Obj(
            "type" -> "section",
            "text" -> ujson.Obj(
              "type" -> "mrkdwn",
              "text" -> "Latest result:"
            )
          )
        ) ++ NewResult(result.meta.resultId, result.data).slackBlocks ++ Vector(
          ujson.Obj(
            "type" -> "section",
            "text" -> ujson.Obj(
              "type" -> "mrkdwn",
              "text" -> "*Other New Result IDs:*"
            )
          ),
          ujson.Obj(
            "type" -> "section",
            "fields" -> excessResultItems.map { str =>
              ujson.Obj(
                "type" -> "mrkdwn",
                "text" -> str
              )
            }
          )
        )
    }

    val cancellationBlocks: Vector[ujson.Obj] = newCancellations match {
      case Seq() => Vector.empty
      case Seq(cancellation) =>
        ujson.Obj(
          "type" -> "header",
          "text" -> ujson.Obj(
            "type" -> "plain_text",
            "text" -> "Standing Query Result Cancelled"
          )
        ) +: CancelledResult(cancellation).slackBlock
      case cancellations =>
        val cancelledIds = cancellations.map(_.uuid.toString)
        val itemsCancelled: Seq[String] =
          if (cancellations.length <= 10) cancelledIds
          else cancelledIds.take(9) :+ s"(${cancellations.length - 9} more)"

        Vector(
          ujson.Obj(
            "type" -> "header",
            "text" -> ujson.Obj(
              "type" -> "plain_text",
              "text" -> "Standing Query Results Cancelled"
            )
          ),
          ujson.Obj(
            "type" -> "section",
            "fields" -> itemsCancelled.map { str =>
              ujson.Obj(
                "type" -> "mrkdwn",
                "text" -> str
              )
            }
          )
        )
    }

    override def slackJson: String = ujson
      .Obj(
        "text" -> "New Standing Query Updates",
        "blocks" -> ujson.Arr.from(newResultsBlocks ++ cancellationBlocks)
      )
      .render()
  }
}
