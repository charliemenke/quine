package com.thatdot.quine.persistor.cassandra.vanilla

import scala.compat.ExecutionContexts
import scala.compat.java8.DurationConverters._
import scala.compat.java8.FutureConverters._
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

import akka.stream.Materializer

import cats.Monad
import cats.implicits._
import com.datastax.oss.driver.api.core.`type`.codec.ExtraTypeCodecs.BLOB_TO_ARRAY
import com.datastax.oss.driver.api.core.`type`.codec.TypeCodec
import com.datastax.oss.driver.api.core.cql.{PreparedStatement, SimpleStatement}
import com.datastax.oss.driver.api.core.{ConsistencyLevel, CqlSession}

import com.thatdot.quine.graph.{StandingQuery, StandingQueryId}
import com.thatdot.quine.persistor.codecs.StandingQueryCodec

trait StandingQueriesColumnNames {
  import CassandraCodecs._
  import syntax._
  val standingQueryCodec: TypeCodec[StandingQuery] = {
    val format = StandingQueryCodec.format
    BLOB_TO_ARRAY.xmap(format.read(_).get, format.write)
  }
  final protected val queryIdColumn: CassandraColumn[StandingQueryId] = CassandraColumn("query_id")
  final protected val queriesColumn: CassandraColumn[StandingQuery] = CassandraColumn("queries")(standingQueryCodec)
}

object StandingQueries extends TableDefinition with StandingQueriesColumnNames {
  protected val tableName = "standing_queries"
  protected val partitionKey: CassandraColumn[StandingQueryId] = queryIdColumn
  protected val clusterKeys = List.empty
  protected val dataColumns: List[CassandraColumn[StandingQuery]] = List(queriesColumn)

  private val createTableStatement: SimpleStatement = makeCreateTableStatement.build.setTimeout(createTableTimeout)

  private val selectAllStatement: SimpleStatement = select
    .column(queriesColumn.name)
    .build()

  private val deleteStatement: SimpleStatement =
    delete
      .where(queryIdColumn.is.eq)
      .build()
      .setIdempotent(true)

  def create(
    session: CqlSession,
    readConsistency: ConsistencyLevel,
    writeConsistency: ConsistencyLevel,
    insertTimeout: FiniteDuration,
    selectTimeout: FiniteDuration,
    shouldCreateTables: Boolean
  )(implicit
    mat: Materializer,
    futureMonad: Monad[Future]
  ): Future[StandingQueries] = {
    logger.debug("Preparing statements for {}", tableName)

    def prepare(statement: SimpleStatement): Future[PreparedStatement] = {
      logger.trace("Preparing {}", statement.getQuery)
      session.prepareAsync(statement).toScala
    }

    val createdSchema =
      if (shouldCreateTables)
        session.executeAsync(createTableStatement).toScala
      else
        Future.unit

    createdSchema.flatMap(_ =>
      (
        prepare(insertStatement.setTimeout(insertTimeout.toJava).setConsistencyLevel(writeConsistency)),
        prepare(deleteStatement.setConsistencyLevel(readConsistency)),
        prepare(selectAllStatement.setTimeout(selectTimeout.toJava).setConsistencyLevel(readConsistency))
      ).mapN(new StandingQueries(session, _, _, _))
    )(ExecutionContexts.parasitic)
  }
}

class StandingQueries(
  session: CqlSession,
  insertStatement: PreparedStatement,
  deleteStatement: PreparedStatement,
  selectAllStatement: PreparedStatement
)(implicit mat: Materializer)
    extends CassandraTable(session)
    with StandingQueriesColumnNames {

  import syntax._

  def nonEmpty(): Future[Boolean] = yieldsResults(StandingQueries.arbitraryRowStatement)

  def persistStandingQuery(standingQuery: StandingQuery): Future[Unit] =
    executeFuture(insertStatement.bindColumns(queryIdColumn.set(standingQuery.id), queriesColumn.set(standingQuery)))

  def removeStandingQuery(standingQuery: StandingQuery): Future[Unit] =
    executeFuture(deleteStatement.bindColumns(queryIdColumn.set(standingQuery.id)))

  def getStandingQueries: Future[List[StandingQuery]] =
    selectColumn(selectAllStatement.bind(), queriesColumn)
}
