package com.thatdot.quine.compiler.cypher

import java.time.{Instant, ZoneId, ZonedDateTime}
import java.util.UUID
import java.util.concurrent.TimeoutException

import scala.collection.concurrent
import scala.concurrent.duration.DurationLong

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.util.Timeout

import com.typesafe.scalalogging.StrictLogging
import org.opencypher.v9_0.ast
import org.opencypher.v9_0.frontend.phases._
import org.opencypher.v9_0.util.StepSequencer.Condition
import org.opencypher.v9_0.util.{InputPosition, Rewriter, bottomUp}

import com.thatdot.quine.graph.cypher.{
  CypherException,
  Expr,
  Func,
  Parameters,
  Proc,
  ProcedureExecutionLocation,
  QueryContext,
  Type,
  UserDefinedFunction,
  UserDefinedFunctionSignature,
  UserDefinedProcedure,
  UserDefinedProcedureSignature,
  Value
}
import com.thatdot.quine.graph.messaging.LiteralMessage._
import com.thatdot.quine.graph.{LiteralOpsGraph, StandingQueryId, StandingQueryOpsGraph, StandingQueryResult}
import com.thatdot.quine.model.{EdgeDirection, HalfEdge, QuineId, QuineIdProvider, QuineValue}

/** Like [[UnresolvedCall]] but where the procedure has been resolved
  *
  * @param resolvedProcedure the procedure that will get called
  * @param unresolvedCall the original call (contains arguments, returns, etc.)
  * INV: All [[ast.CallClause]]s are either [[ast.UnresolvedCall]] or [[QuineProcedureCall]], and in a fully
  * compiled query, all [[ast.CallClause]] are [[QuineProcedureCall]]
  */
final case class QuineProcedureCall(
  resolvedProcedure: UserDefinedProcedure,
  unresolvedCall: ast.UnresolvedCall
) extends ast.CallClause {

  override def semanticCheck = unresolvedCall.semanticCheck

  override def returnColumns = unresolvedCall.returnColumns

  override def containsNoUpdates: Boolean = !resolvedProcedure.canContainUpdates

  override val position: InputPosition = unresolvedCall.position
}

/** Re-write unresolved calls into variants that are resolved according to a
  * global map of UDPs
  */
case object resolveCalls extends StatementRewriter {

  /** Procedures known at Quine compile-time
    * NB some of these are only stubs -- see [[StubbedUserDefinedProcedure]]
    */
  val builtInProcedures: List[UserDefinedProcedure] = List(
    CypherIndexes,
    CypherRelationshipTypes,
    CypherFunctions,
    CypherProcedures,
    CypherPropertyKeys,
    CypherLabels,
    CypherDoWhen,
    CypherDoIt,
    CypherDoCase,
    CypherRunTimeboxed,
    CypherSleep,
    CypherCreateRelationship,
    CypherCreateSetLabels,
    RecentNodes,
    RecentNodeIds,
    JsonLoad,
    IncrementCounter,
    CypherLogging,
    CypherDebugNode,
    CypherGetDistinctIDSqSubscriberResults,
    CypherGetDistinctIdSqSubscriptionResults,
    CypherDebugSleep,
    ReifyTime
  )

  /** This map is only meant to maintain backward compatibility for a short time. */
  val deprecatedNames: Map[String, UserDefinedProcedure] = Map.empty

  private val procedures: concurrent.Map[String, UserDefinedProcedure] = Proc.userDefinedProcedures
  builtInProcedures.foreach(registerUserDefinedProcedure)
  procedures ++= deprecatedNames.map { case (rename, p) => rename.toLowerCase -> p }

  val rewriteCall: PartialFunction[AnyRef, AnyRef] = { case uc: ast.UnresolvedCall =>
    val ucName = (uc.procedureNamespace.parts :+ uc.procedureName.name).mkString(".")
    procedures.get(ucName.toLowerCase) match {
      case None => uc
      case Some(proc) => QuineProcedureCall(proc, uc)
    }
  }

  override def instance(ctx: BaseContext): Rewriter = bottomUp(Rewriter.lift(rewriteCall))

  // TODO: add to this
  override def postConditions: Set[Condition] = Set.empty
}

/** Get recently touched node IDs from shards */
object RecentNodeIds extends UserDefinedProcedure {
  val name = "recentNodeIds"
  val canContainUpdates = false
  val isIdempotent = false
  val canContainAllNodeScan = false
  val signature: UserDefinedProcedureSignature = UserDefinedProcedureSignature(
    arguments = Vector("count" -> Type.Integer),
    outputs = Vector("nodeId" -> Type.Anything),
    description = "Fetch the specified number of IDs of nodes from the in-memory cache"
  )

  def call(
    context: QueryContext,
    arguments: Seq[Value],
    location: ProcedureExecutionLocation
  )(implicit
    parameters: Parameters,
    timeout: Timeout
  ): Source[Vector[Value], _] = {
    val limit: Int = arguments match {
      case Seq() => 10
      case Seq(Expr.Integer(l)) => l.toInt
      case other => throw wrongSignature(other)
    }

    Source.lazyFutureSource { () =>
      location.graph
        .recentNodes(limit, location.atTime)
        .map { (nodes: Set[QuineId]) =>
          Source
            .fromIterator(() => nodes.iterator)
            .map(qid => Vector(Expr.Str(qid.pretty(location.idProvider))))
        }(location.graph.nodeDispatcherEC)
    }
  }
}

/** Get recently touched nodes from shards */
object RecentNodes extends UserDefinedProcedure {
  val name = "recentNodes"
  val canContainUpdates = false
  val isIdempotent = false
  val canContainAllNodeScan = false
  val signature: UserDefinedProcedureSignature = UserDefinedProcedureSignature(
    arguments = Vector("count" -> Type.Integer),
    outputs = Vector("node" -> Type.Node),
    description = "Fetch the specified number of nodes from the in-memory cache"
  )

  def call(
    context: QueryContext,
    arguments: Seq[Value],
    location: ProcedureExecutionLocation
  )(implicit
    parameters: Parameters,
    timeout: Timeout
  ): Source[Vector[Value], _] = {
    val limit: Int = arguments match {
      case Seq() => 10
      case Seq(Expr.Integer(l)) => l.toInt
      case other => throw wrongSignature(other)
    }
    val atTime = location.atTime
    val graph = LiteralOpsGraph.getOrThrow(s"`$name` procedure", location.graph)

    Source.lazyFutureSource { () =>
      graph
        .recentNodes(limit, atTime)
        .map { (nodes: Set[QuineId]) =>
          Source
            .fromIterator(() => nodes.iterator)
            .mapAsync(parallelism = 1)(UserDefinedProcedure.getAsCypherNode(_, atTime, graph))
            .map(Vector(_))
        }(location.graph.nodeDispatcherEC)
    }
  }
}

// This is required to be defined for `cypher-shell` version 4.0 and above to work
final case class CypherGetRoutingTable(addresses: Seq[String]) extends UserDefinedProcedure {
  val name = "dbms.cluster.routing.getRoutingTable"
  val canContainUpdates = false
  val isIdempotent = true
  val canContainAllNodeScan = false
  val signature: UserDefinedProcedureSignature = UserDefinedProcedureSignature(
    arguments = Vector("context" -> Type.Map, "database" -> Type.Str),
    outputs = Vector("ttl" -> Type.Integer, "servers" -> Type.List(Type.Str)),
    description = ""
  )

  // TODO: use the argument(s)
  def call(
    context: QueryContext,
    arguments: Seq[Value],
    location: ProcedureExecutionLocation
  )(implicit
    parameters: Parameters,
    timeout: Timeout
  ): Source[Vector[Value], NotUsed] =
    Source.single(
      Vector(
        Expr.Integer(-1L),
        Expr.List(Vector("WRITE", "READ", "ROUTE").map { role =>
          Expr.Map(
            Map(
              "addresses" -> Expr.List(addresses.map(Expr.Str(_)).toVector),
              "role" -> Expr.Str(role)
            )
          )
        })
      )
    )
}

object JsonLoad extends UserDefinedProcedure {
  val name = "loadJsonLines"
  val canContainUpdates = false
  val isIdempotent = true
  val canContainAllNodeScan = false
  val signature: UserDefinedProcedureSignature = UserDefinedProcedureSignature(
    arguments = Vector("url" -> Type.Str),
    outputs = Vector("value" -> Type.Anything),
    description = "Load a line-base JSON file, emitting one record per line"
  )

  def call(
    context: QueryContext,
    arguments: Seq[Value],
    location: ProcedureExecutionLocation
  )(implicit
    parameters: Parameters,
    timeout: Timeout
  ): Source[Vector[Value], _] = {
    val urlOrPath = arguments match {
      case Seq(Expr.Str(s)) => s
      case other => throw wrongSignature(other)
    }

    Source.fromIterator(() =>
      scala.io.Source
        .fromURL(urlOrPath)
        .getLines()
        .map((line: String) => Vector(Value.fromJson(ujson.read(line))))
    )
  }
}

/** Procedures which are not currently implemented in Quine, but which must be present
  * for certain external systems to operate with Quine (eg cypher-shell or neo4j-browser)
  */
abstract class StubbedUserDefinedProcedure(
  override val name: String,
  outputColumnNames: Vector[String]
) extends UserDefinedProcedure {
  // Stubbed procedures are used for compatibility with other systems, therefore we avoid any Quine-specific semantic analysis
  val canContainUpdates = false
  val isIdempotent = true
  val canContainAllNodeScan = false
  val signature: UserDefinedProcedureSignature = UserDefinedProcedureSignature(
    arguments = Vector.empty,
    outputs = outputColumnNames.map(_ -> Type.Anything),
    description = ""
  )

  def call(
    context: QueryContext,
    arguments: Seq[Value],
    location: ProcedureExecutionLocation
  )(implicit
    parameters: Parameters,
    timeout: Timeout
  ): Source[Vector[Value], _] = Source.empty
}

object CypherIndexes
    extends StubbedUserDefinedProcedure(
      name = "db.indexes",
      outputColumnNames = Vector(
        "description",
        "indexName",
        "tokenNames",
        "properties",
        "state",
        "type",
        "progress",
        "provider",
        "id",
        "failureMessage"
      )
    )

object CypherRelationshipTypes
    extends StubbedUserDefinedProcedure(
      name = "db.relationshipTypes",
      outputColumnNames = Vector("relationshipType")
    )

object CypherPropertyKeys
    extends StubbedUserDefinedProcedure(
      name = "db.propertyKeys",
      outputColumnNames = Vector("propertyKey")
    )

object CypherLabels
    extends StubbedUserDefinedProcedure(
      name = "dbms.labels",
      outputColumnNames = Vector("label")
    )

/** Increment an integer property on a node atomically (doing the get and the
  * set in one step with no intervening operation)
  */
object IncrementCounter extends UserDefinedProcedure {
  val name = "incrementCounter"
  val canContainUpdates = true
  val isIdempotent = false
  val canContainAllNodeScan = false
  val signature: UserDefinedProcedureSignature = UserDefinedProcedureSignature(
    arguments = Vector("node" -> Type.Node, "key" -> Type.Str, "amount" -> Type.Integer),
    outputs = Vector("count" -> Type.Integer),
    description =
      "Atomically increment an integer property on a node by a certain amount, returning the resultant value"
  )

  def call(
    context: QueryContext,
    arguments: Seq[Value],
    location: ProcedureExecutionLocation
  )(implicit
    parameters: Parameters,
    timeout: Timeout
  ): Source[Vector[Value], NotUsed] = {
    import location._

    // Pull out the arguments
    val (nodeId, propertyKey, incrementQuantity) = arguments match {
      case Seq(Expr.Node(id, _, _), Expr.Str(key)) => (id, key, 1L)
      case Seq(Expr.Node(id, _, _), Expr.Str(key), Expr.Integer(amount)) => (id, key, amount)
      case other => throw wrongSignature(other)
    }

    Source.lazyFuture { () =>
      nodeId
        .?(IncrementProperty(Symbol(propertyKey), incrementQuantity, _))
        .map {
          case IncrementProperty.Success(newCount) => Vector(Expr.Integer(newCount))
          case IncrementProperty.Failed(valueFound) =>
            throw CypherException.TypeMismatch(
              expected = Seq(Type.Integer),
              actualValue = Expr.fromQuineValue(valueFound),
              context = "`incrementCounter` procedure"
            )
        }(location.graph.nodeDispatcherEC)
    }
  }
}

object CypherLogging extends UserDefinedProcedure with StrictLogging {
  val name = "log"
  val canContainUpdates = false
  val isIdempotent = true
  val canContainAllNodeScan = false
  val signature: UserDefinedProcedureSignature = UserDefinedProcedureSignature(
    arguments = Vector("level" -> Type.Str, "value" -> Type.Anything),
    outputs = Vector("log" -> Type.Str),
    description = "Log the input argument to console"
  )

  def call(
    context: QueryContext,
    arguments: Seq[Value],
    location: ProcedureExecutionLocation
  )(implicit
    parameters: Parameters,
    timeout: Timeout
  ): Source[Vector[Value], NotUsed] = {

    val prettyStr: String = arguments match {
      case Seq(Expr.Str(lvl), any) =>
        val prettied = any.pretty
        lvl match {
          case "error" => logger.error(prettied)
          case "warn" | "warning" => logger.warn(prettied)
          case "info" => logger.info(prettied)
          case "debug" => logger.debug(prettied)
          case "trace" => logger.trace(prettied)
          case other =>
            logger.error(s"Unrecognized log level $other, falling back to `warn`")
            logger.warn(prettied)
        }
        prettied

      case Seq(any) =>
        val prettied = any.pretty
        logger.warn(prettied)
        prettied

      case other => throw wrongSignature(other)
    }

    Source.single(Vector(Expr.Str(prettyStr)))
  }
}

object CypherDebugNode extends UserDefinedProcedure {
  val name = "debug.node"
  val canContainUpdates = false
  val isIdempotent = true
  val canContainAllNodeScan = false
  val signature: UserDefinedProcedureSignature = UserDefinedProcedureSignature(
    arguments = Vector("node" -> Type.Anything),
    outputs = Vector(
      "atTime" -> Type.LocalDateTime,
      "properties" -> Type.Map,
      "edges" -> Type.ListOfAnything,
      "latestUpdateMillisAfterSnapshot" -> Type.Integer,
      "subscribers" -> Type.Str,
      "subscriptions" -> Type.Str,
      "multipleValuesStandingQueryStates" -> Type.ListOfAnything,
      "journal" -> Type.ListOfAnything,
      "graphNodeHashCode" -> Type.Integer
    ),
    description = "Log the internal state of a node"
  )

  private[this] def halfEdge2Value(edge: HalfEdge)(implicit idProvider: QuineIdProvider): Value =
    Expr.Map(
      Map(
        "edgeType" -> Expr.Str(edge.edgeType.name),
        "direction" -> Expr.Str(edge.direction.toString),
        "other" -> Expr.fromQuineValue(idProvider.qidToValue(edge.other))
      )
    )

  private[this] def locallyRegisteredStandingQuery2Value(q: LocallyRegisteredStandingQuery): Value =
    Expr.Map(
      Map(
        "id" -> Expr.Str(q.id),
        "globalId" -> Expr.Str(q.globalId),
        "subscribers" -> Expr.List(q.subscribers.view.map(Expr.Str(_)).toVector),
        "state" -> Expr.Str(q.state)
      )
    )

  def call(
    context: QueryContext,
    arguments: Seq[Value],
    location: ProcedureExecutionLocation
  )(implicit
    parameters: Parameters,
    timeout: Timeout
  ): Source[Vector[Value], NotUsed] = {

    val graph = LiteralOpsGraph.getOrThrow(s"`$name` procedure", location.graph)
    implicit val idProv: QuineIdProvider = graph.idProvider

    val node: QuineId = arguments match {
      case Seq(nodeLike) =>
        UserDefinedProcedure.extractQuineId(nodeLike) match {
          case None =>
            throw CypherException.Runtime(s"`$name` expects a node or node ID argument, but got $nodeLike")
          case Some(qid) =>
            qid
        }
      case other =>
        throw wrongSignature(other)
    }

    Source.lazyFuture { () =>
      graph.literalOps
        .logState(node, location.atTime)
        .map {
          case NodeInternalState(
                atTime,
                properties,
                edges,
                latestUpdateMillisAfterSnapshot,
                subscribers,
                subscriptions,
                _,
                _,
                multipleValuesStandingQueryStates,
                journal,
                graphNodeHashCode
              ) =>
            Vector(
              atTime
                .map(t =>
                  Expr.DateTime(ZonedDateTime.ofInstant(Instant.ofEpochMilli(t.millis), ZoneId.systemDefault()))
                )
                .getOrElse(Expr.Null),
              Expr.Map(properties.view.map(kv => kv._1.name -> Expr.Str(kv._2)).toMap),
              Expr.List(edges.view.map(halfEdge2Value).toVector),
              latestUpdateMillisAfterSnapshot match {
                case None => Expr.Null
                case Some(eventTime) => Expr.Integer(eventTime.millis)
              },
              Expr.Str(subscribers.mkString(",")),
              Expr.Str(subscriptions.mkString(",")),
              Expr.List(multipleValuesStandingQueryStates.map(locallyRegisteredStandingQuery2Value)),
              Expr.List(journal.map(e => Expr.Str(e.toString)).toVector),
              Expr.Integer(graphNodeHashCode)
            )
        }(location.graph.nodeDispatcherEC)
    }
  }
}

object CypherGetDistinctIDSqSubscriberResults extends UserDefinedProcedure {
  def name: String = "subscribers"
  def canContainUpdates: Boolean = false
  def isIdempotent: Boolean = true
  def canContainAllNodeScan: Boolean = false

  val signature: UserDefinedProcedureSignature = UserDefinedProcedureSignature(
    arguments = Vector("node" -> Type.Anything),
    outputs = Vector(
      "queryId" -> Type.Integer,
      "queryDepth" -> Type.Integer,
      "receiverId" -> Type.Str,
      "lastResult" -> Type.Anything
    ),
    description = "Return the current state of the standing query subscribers."
  )

  def call(context: QueryContext, arguments: Seq[Value], location: ProcedureExecutionLocation)(implicit
    parameters: Parameters,
    timeout: Timeout
  ): Source[Vector[Value], _] = {
    val graph: LiteralOpsGraph = LiteralOpsGraph.getOrThrow(s"`$name` procedure", location.graph)
    implicit val idProv: QuineIdProvider = location.graph.idProvider

    val node: QuineId = arguments match {
      case Seq(nodeLike) =>
        UserDefinedProcedure.extractQuineId(nodeLike) match {
          case None =>
            throw CypherException.Runtime(s"`$name` expects a node or node ID argument, but got $nodeLike")
          case Some(qid) =>
            qid
        }
      case other =>
        throw wrongSignature(other)
    }

    Source.lazyFutureSource { () =>
      graph.literalOps
        .getSqResults(node)
        .map(sqr =>
          Source.fromIterator { () =>
            sqr.subscribers.map { s =>
              Vector(
                Expr.Integer(s.dgnId.toLong),
                Expr.Str(s.qid.pretty),
                s.lastResult.fold[Value](Expr.Null)(r => Expr.Bool(r))
              )
            }.toIterator
          }
        )(location.graph.nodeDispatcherEC)
    }
  }
}

object CypherGetDistinctIdSqSubscriptionResults extends UserDefinedProcedure {
  def name: String = "subscriptions"
  def canContainUpdates: Boolean = false
  def isIdempotent: Boolean = true
  def canContainAllNodeScan: Boolean = false

  val signature: UserDefinedProcedureSignature = UserDefinedProcedureSignature(
    arguments = Vector("node" -> Type.Anything),
    outputs = Vector(
      "queryId" -> Type.Integer,
      "queryDepth" -> Type.Integer,
      "receiverId" -> Type.Str,
      "lastResult" -> Type.Anything
    ),
    description = "Return the current state of the standing query subscriptions."
  )

  def call(context: QueryContext, arguments: Seq[Value], location: ProcedureExecutionLocation)(implicit
    parameters: Parameters,
    timeout: Timeout
  ): Source[Vector[Value], _] = {
    val graph: LiteralOpsGraph = LiteralOpsGraph.getOrThrow(s"`$name` procedure", location.graph)
    implicit val idProv: QuineIdProvider = location.graph.idProvider

    val node: QuineId = arguments match {
      case Seq(nodeLike) =>
        UserDefinedProcedure.extractQuineId(nodeLike) match {
          case None =>
            throw CypherException.Runtime(s"`$name` expects a node or node ID argument, but got $nodeLike")
          case Some(qid) =>
            qid
        }
      case other =>
        throw wrongSignature(other)
    }

    Source.lazyFutureSource { () =>
      graph.literalOps
        .getSqResults(node)
        .map(sqr =>
          Source.fromIterator { () =>
            sqr.subscriptions.map { s =>
              Vector(
                Expr.Integer(s.dgnId.toLong),
                Expr.Str(s.qid.pretty),
                s.lastResult.fold[Value](Expr.Null)(r => Expr.Bool(r))
              )
            }.toIterator
          }
        )(location.graph.nodeDispatcherEC)
    }
  }
}

object CypherDebugSleep extends UserDefinedProcedure {
  val name = "debug.sleep"
  val canContainUpdates = false
  val isIdempotent = true
  val canContainAllNodeScan = false
  val signature: UserDefinedProcedureSignature = UserDefinedProcedureSignature(
    arguments = Vector("node" -> Type.Anything),
    outputs = Vector.empty,
    description = "Request a node sleep"
  )

  def call(
    context: QueryContext,
    arguments: Seq[Value],
    location: ProcedureExecutionLocation
  )(implicit
    parameters: Parameters,
    timeout: Timeout
  ): Source[Vector[Value], NotUsed] = {

    val graph = location.graph
    implicit val idProv: QuineIdProvider = graph.idProvider

    val node: QuineId = arguments match {
      case Seq(nodeLike) =>
        UserDefinedProcedure.extractQuineId(nodeLike) match {
          case None =>
            throw CypherException.Runtime(s"`$name` expects a node or node ID argument, but got $nodeLike")
          case Some(qid) =>
            qid
        }
      case other =>
        throw wrongSignature(other)
    }

    Source.lazyFuture { () =>
      graph.requestNodeSleep(node).map(_ => Vector.empty)(location.graph.nodeDispatcherEC)
    }
  }
}

object CypherBuiltinFunctions extends UserDefinedProcedure {
  val name = "help.builtins"
  val canContainUpdates = false
  val isIdempotent = true
  val canContainAllNodeScan = false
  val signature: UserDefinedProcedureSignature = UserDefinedProcedureSignature(
    arguments = Vector.empty,
    outputs = Vector("name" -> Type.Str, "signature" -> Type.Str, "description" -> Type.Str),
    description = "List built-in cypher functions"
  )

  def call(
    context: QueryContext,
    arguments: Seq[Value],
    location: ProcedureExecutionLocation
  )(implicit
    parameters: Parameters,
    timeout: Timeout
  ): Source[Vector[Value], NotUsed] = {

    arguments match {
      case Seq() =>
      case other => throw wrongSignature(other)
    }

    Source
      .fromIterator(() => Func.builtinFunctions.sortBy(_.name).iterator)
      .map(bfc => Vector(Expr.Str(bfc.name), Expr.Str(bfc.signature), Expr.Str(bfc.description)))
  }
}

object CypherFunctions extends UserDefinedProcedure {
  val name = "help.functions"
  val canContainUpdates = false
  val isIdempotent = true
  val canContainAllNodeScan = false
  val signature: UserDefinedProcedureSignature = UserDefinedProcedureSignature(
    arguments = Vector.empty,
    outputs = Vector("name" -> Type.Str, "signature" -> Type.Str, "description" -> Type.Str),
    description = "List registered functions"
  )

  def call(
    context: QueryContext,
    arguments: Seq[Value],
    location: ProcedureExecutionLocation
  )(implicit
    parameters: Parameters,
    timeout: Timeout
  ): Source[Vector[Value], NotUsed] = {

    arguments match {
      case Seq() =>
      case other => throw wrongSignature(other)
    }

    val builtins =
      Func.builtinFunctions
        .sortBy(_.name)
        .map(bfc => Vector(Expr.Str(bfc.name), Expr.Str(bfc.signature), Expr.Str(bfc.description)))

    val userDefined =
      Func.userDefinedFunctions.values.toList
        .sortBy(_.name)
        .flatMap { (udf: UserDefinedFunction) =>
          val name = udf.name
          udf.signatures.toVector.map { (udfSig: UserDefinedFunctionSignature) =>
            Vector(Expr.Str(name), Expr.Str(udfSig.pretty(name)), Expr.Str(udfSig.description))
          }
        }

    Source((builtins ++ userDefined).sortBy(_.head.string))
  }
}

object CypherProcedures extends UserDefinedProcedure {
  val name = "help.procedures"
  val canContainUpdates = false
  val isIdempotent = true
  val canContainAllNodeScan = false
  val signature: UserDefinedProcedureSignature = UserDefinedProcedureSignature(
    arguments = Vector.empty,
    outputs = Vector(
      "name" -> Type.Str,
      "signature" -> Type.Str,
      "description" -> Type.Str,
      "mode" -> Type.Str
    ),
    description = "List registered procedures"
  )

  def call(
    context: QueryContext,
    arguments: Seq[Value],
    location: ProcedureExecutionLocation
  )(implicit
    parameters: Parameters,
    timeout: Timeout
  ): Source[Vector[Value], NotUsed] = {

    arguments match {
      case Seq() =>
      case other => throw wrongSignature(other)
    }

    Source
      .fromIterator(() => Proc.userDefinedProcedures.values.toList.sortBy(_.name).iterator)
      .map { (udp: UserDefinedProcedure) =>
        val name = udp.name
        val sig = udp.signature.pretty(udp.name)
        val description = udp.signature.description
        val mode = if (udp.canContainUpdates) "WRITE" else "READ"
        Vector(Expr.Str(name), Expr.Str(sig), Expr.Str(description), Expr.Str(mode))
      }
  }
}

object CypherDoWhen extends UserDefinedProcedure {
  val name = "do.when"
  val canContainUpdates = true
  val isIdempotent = false
  val canContainAllNodeScan = false
  val signature: UserDefinedProcedureSignature = UserDefinedProcedureSignature(
    arguments = Vector(
      "condition" -> Type.Bool,
      "ifQuery" -> Type.Str,
      "elseQuery" -> Type.Str,
      "params" -> Type.Map
    ),
    outputs = Vector("value" -> Type.Map),
    description = "Depending on the condition execute ifQuery or elseQuery"
  )

  def call(
    context: QueryContext,
    arguments: Seq[Value],
    location: ProcedureExecutionLocation
  )(implicit
    parameters: Parameters,
    timeout: Timeout
  ): Source[Vector[Value], NotUsed] = {

    val (cond: Boolean, ifQ: String, elseQ: String, params: Map[String, Value]) = arguments match {
      case Seq(Expr.Bool(c), Expr.Str(ifQ)) => (c, ifQ, "", Map.empty)
      case Seq(Expr.Bool(c), Expr.Str(ifQ), Expr.Str(elseQ)) => (c, ifQ, elseQ, Map.empty)
      case Seq(Expr.Bool(c), Expr.Str(ifQ), Expr.Str(elseQ), Expr.Map(p)) => (c, ifQ, elseQ, p)
      case other => throw wrongSignature(other)
    }

    val queryToExecute = if (cond) ifQ else elseQ

    if (queryToExecute == "") {
      Source.single(Vector(Expr.Map(Map.empty)))
    } else {
      val subQueryResults = queryCypherValues(
        queryToExecute,
        parameters = params,
        initialColumns = params,
        atTime = location.atTime
      )(
        location.graph
      )

      subQueryResults.results.map { (row: Vector[Value]) =>
        Vector(Expr.Map(subQueryResults.columns.view.map(_.name).zip(row.view).toMap))
      }
    }
  }
}

object CypherDoIt extends UserDefinedProcedure {
  val name = "cypher.doIt"
  val canContainUpdates = true
  val isIdempotent = false
  val canContainAllNodeScan = false
  val signature: UserDefinedProcedureSignature = UserDefinedProcedureSignature(
    arguments = Vector("cypher" -> Type.Str, "params" -> Type.Map),
    outputs = Vector("value" -> Type.Map),
    description = "Executes a Cypher query with the given parameters"
  )

  def call(
    context: QueryContext,
    arguments: Seq[Value],
    location: ProcedureExecutionLocation
  )(implicit
    parameters: Parameters,
    timeout: Timeout
  ): Source[Vector[Value], NotUsed] = {

    val (query: String, parameters: Map[String, Value]) = arguments match {
      case Seq(Expr.Str(query)) => query -> Map.empty
      case Seq(Expr.Str(query), Expr.Map(parameters)) => (query, parameters)
      case other => throw wrongSignature(other)
    }

    val subQueryResults = queryCypherValues(
      query,
      parameters = parameters,
      initialColumns = parameters,
      atTime = location.atTime
    )(
      location.graph
    )

    subQueryResults.results.map { (row: Vector[Value]) =>
      Vector(Expr.Map(subQueryResults.columns.view.map(_.name).zip(row.view).toMap))
    }
  }
}

object CypherDoCase extends UserDefinedProcedure {
  val name = "cypher.do.case"
  val canContainUpdates = true
  val isIdempotent = false
  val canContainAllNodeScan = false
  val signature: UserDefinedProcedureSignature = UserDefinedProcedureSignature(
    arguments = Vector("conditionals" -> Type.ListOfAnything, "elseQuery" -> Type.Str, "params" -> Type.Map),
    outputs = Vector("value" -> Type.Map),
    description = "Given a list of conditional/query pairs, execute the first query with a true conditional"
  )

  def call(
    context: QueryContext,
    arguments: Seq[Value],
    location: ProcedureExecutionLocation
  )(implicit
    parameters: Parameters,
    timeout: Timeout
  ): Source[Vector[Value], NotUsed] = {

    val (conditionals: Vector[Value], elseQuery: String, parameters: Map[String, Value]) = arguments match {
      case Seq(Expr.List(conds)) => (conds, "", Map.empty)
      case Seq(Expr.List(conds), Expr.Str(els)) => (conds, els, Map.empty)
      case Seq(Expr.List(conds), Expr.Str(els), Expr.Map(params)) => (conds, els, params)
      case other => throw wrongSignature(other)
    }

    // Iterate through the conditions and queries to find the right matching query
    val matchingQuery: Option[String] = conditionals
      .grouped(2)
      .map {
        case Vector(Expr.Bool(cond), Expr.Str(query)) => cond -> query
        case Vector(_: Expr.Bool, other) =>
          throw CypherException.TypeMismatch(Seq(Type.Str), other, s"query statement in `$name`)")
        case Vector(other, _) =>
          throw CypherException.TypeMismatch(Seq(Type.Bool), other, s"condition in `$name`)")
        case _ =>
          throw CypherException.Runtime(
            s"`$name` expects each condition to be followed by a query, " +
            "but the list of conditions and queries has odd length ${conditionals.length}"
          )
      }
      .collectFirst { case (true, query) => query }
      .orElse(Some(elseQuery))
      .filter(_ != "")

    matchingQuery match {
      case None => Source.single(Vector(Expr.Map(Map.empty)))
      case Some(query) =>
        val subQueryResults = queryCypherValues(
          query,
          parameters = parameters,
          initialColumns = parameters,
          atTime = location.atTime
        )(
          location.graph
        )

        subQueryResults.results.map { (row: Vector[Value]) =>
          Vector(Expr.Map(subQueryResults.columns.view.map(_.name).zip(row.view).toMap))
        }
    }
  }
}

object CypherRunTimeboxed extends UserDefinedProcedure {
  val name = "cypher.runTimeboxed"
  val canContainUpdates = true
  val isIdempotent = false
  val canContainAllNodeScan = false
  val signature: UserDefinedProcedureSignature = UserDefinedProcedureSignature(
    arguments = Vector("cypher" -> Type.Str, "params" -> Type.Map, "timeout" -> Type.Integer),
    outputs = Vector("value" -> Type.Map),
    description = "Executes a Cypher query with the given parameters but abort after a certain number of milliseconds"
  )

  def call(
    context: QueryContext,
    arguments: Seq[Value],
    location: ProcedureExecutionLocation
  )(implicit
    parameters: Parameters,
    timeout: Timeout
  ): Source[Vector[Value], NotUsed] = {

    val (query: String, parameters: Map[String, Value], t: Long) = arguments match {
      case Seq(Expr.Str(query), Expr.Map(parameters), Expr.Integer(t)) => (query, parameters, t)
      case other => throw wrongSignature(other)
    }

    val subQueryResults = queryCypherValues(
      query,
      parameters = parameters,
      initialColumns = parameters,
      atTime = location.atTime
    )(
      location.graph
    )

    subQueryResults.results
      .completionTimeout(t.milliseconds)
      .recoverWithRetries(1, { case _: TimeoutException => Source.empty })
      .map { (row: Vector[Value]) =>
        Vector(Expr.Map(subQueryResults.columns.view.map(_.name).zip(row.view).toMap))
      }
  }
}

object CypherSleep extends UserDefinedProcedure {
  val name = "util.sleep"
  val canContainUpdates = false
  val isIdempotent = true
  val canContainAllNodeScan = false
  val signature: UserDefinedProcedureSignature = UserDefinedProcedureSignature(
    arguments = Vector("duration" -> Type.Integer),
    outputs = Vector.empty,
    description = "Sleep for a certain number of milliseconds"
  )

  def call(
    context: QueryContext,
    arguments: Seq[Value],
    location: ProcedureExecutionLocation
  )(implicit
    parameters: Parameters,
    timeout: Timeout
  ): Source[Vector[Value], NotUsed] = {

    val sleepMillis: Long = arguments match {
      case Seq(Expr.Integer(t)) => t
      case other => throw wrongSignature(other)
    }

    Source
      .single(Vector.empty[Value])
      .initialDelay(sleepMillis.milliseconds)
  }
}

object CypherCreateRelationship extends UserDefinedProcedure {
  val name = "create.relationship"
  val canContainUpdates = true
  val isIdempotent = true
  val canContainAllNodeScan = false
  val signature: UserDefinedProcedureSignature = UserDefinedProcedureSignature(
    arguments = Vector("from" -> Type.Node, "relType" -> Type.Str, "props" -> Type.Map, "to" -> Type.Node),
    outputs = Vector("rel" -> Type.Relationship),
    description = "Create a relationship with a potentially dynamic name"
  )

  def call(
    context: QueryContext,
    arguments: Seq[Value],
    location: ProcedureExecutionLocation
  )(implicit
    parameters: Parameters,
    timeout: Timeout
  ): Source[Vector[Value], NotUsed] = {
    import location._

    val (from, label, to): (QuineId, Symbol, QuineId) = arguments match {
      case Seq(Expr.Node(from, _, _), Expr.Str(name), Expr.Map(_), Expr.Node(to, _, _)) => (from, Symbol(name), to)
      case other => throw wrongSignature(other)
    }

    Source.lazyFuture { () =>
      val one = from ? (AddHalfEdgeCommand(HalfEdge(label, EdgeDirection.Outgoing, to), _))
      val two = to ? (AddHalfEdgeCommand(HalfEdge(label, EdgeDirection.Incoming, from), _))
      one.zipWith(two)((_, _) => Vector(Expr.Relationship(from, label, Map.empty, to)))(location.graph.nodeDispatcherEC)
    }
  }
}

object CypherCreateSetLabels extends UserDefinedProcedure {
  val name = "create.setLabels"
  val canContainUpdates = true
  val isIdempotent = true
  val canContainAllNodeScan = false
  val signature: UserDefinedProcedureSignature = UserDefinedProcedureSignature(
    arguments = Vector("node" -> Type.Node, "labels" -> Type.List(Type.Str)),
    outputs = Vector.empty,
    description = "Set the label on the specified input nodes"
  )

  def call(
    context: QueryContext,
    arguments: Seq[Value],
    location: ProcedureExecutionLocation
  )(implicit
    parameters: Parameters,
    timeout: Timeout
  ): Source[Vector[Value], NotUsed] = {
    import location._

    val (node, labels): (QuineId, Set[Symbol]) = arguments match {
      case Seq(Expr.Node(from, _, _), Expr.List(labels)) =>
        val stringLabels = Set.newBuilder[Symbol]
        for (label <- labels)
          label match {
            case Expr.Str(l) => stringLabels += Symbol(l)
            case _ => throw wrongSignature(arguments)
          }
        from -> stringLabels.result()
      case other => throw wrongSignature(other)
    }

    Source
      .lazyFuture(() => node ? (SetLabels(labels, _)))
      .map(_ => Vector.empty[Value])
  }
}

/** Lookup a standing query by user-facing name, yielding its [[StandingQueryResults]] as they are produced
  * Registered by registerUserDefinedProcedure at runtime by product entrypoint and in docs' GenerateCypherTables
  * NB despite the name including `wiretap`, this is implemented as an akka-streams map
  */
class CypherStandingWiretap(lookupByName: String => Option[StandingQueryId]) extends UserDefinedProcedure {
  val name = "standing.wiretap"
  val canContainUpdates = false
  val isIdempotent = true
  val canContainAllNodeScan = false
  val signature: UserDefinedProcedureSignature = UserDefinedProcedureSignature(
    arguments = Vector("options" -> Type.Map),
    outputs = Vector("data" -> Type.Map, "meta" -> Type.Map),
    description = "Wire-tap the results of a standing query"
  )

  def call(
    context: QueryContext,
    arguments: Seq[Value],
    location: ProcedureExecutionLocation
  )(implicit
    parameters: Parameters,
    timeout: Timeout
  ): Source[Vector[Value], NotUsed] = {

    val standingQueryId: StandingQueryId = arguments match {
      case Seq(Expr.Map(optionsMap)) =>
        val remainingOptions = scala.collection.mutable.Map(optionsMap.toSeq: _*)

        // User specified the name of the standing query
        val standingQueryName = remainingOptions.remove("name").map {
          case Expr.Str(name) => name
          case other =>
            throw CypherException.TypeMismatch(
              Seq(Type.Str),
              other,
              "`name` field in options map"
            )
        }

        // User specified the standing query ID
        val standingQueryIdStr = remainingOptions.remove("id").map {
          case Expr.Str(sqId) => sqId
          case other =>
            throw CypherException.TypeMismatch(
              Seq(Type.Str),
              other,
              "`id` field in options map"
            )
        }

        // Disallow unknown fields
        if (remainingOptions.nonEmpty) {
          throw CypherException.Runtime(
            "Unknown fields in options map: " + remainingOptions.keys.mkString("`", "`, `", "`")
          )
        }

        (standingQueryName, standingQueryIdStr) match {
          case (Some(name), None) =>
            lookupByName(name).getOrElse {
              throw CypherException.Runtime(s"Cannot find standing query with name `$name`")
            }
          case (None, Some(strId)) =>
            try StandingQueryId(UUID.fromString(strId))
            catch {
              case _: IllegalArgumentException =>
                throw CypherException.Runtime(s"Expected standing query ID to be UUID, but got `$strId`")
            }
          case (None, None) =>
            throw CypherException.Runtime("One of `name` or `id` needs to be specified")
          case (Some(_), Some(_)) =>
            throw CypherException.Runtime("Only one of `name` or `id` needs to be specified")
        }

      case other => throw wrongSignature(other)
    }

    val graph: StandingQueryOpsGraph = StandingQueryOpsGraph(location.graph) match {
      case None =>
        val msg = s"`$name` procedure requires a graph that implements StandingQueryOperations"
        return Source.failed(new IllegalArgumentException(msg))
      case Some(g) => g
    }

    graph
      .wireTapStandingQuery(standingQueryId)
      .getOrElse(throw CypherException.Runtime(s"Cannot find standing query with id `$standingQueryId`"))
      .map { case StandingQueryResult(meta, data) =>
        val dataMap = Expr.fromQuineValue(QuineValue.Map(data))
        val metaMap = Expr.fromQuineValue(QuineValue.Map(meta.toMap))
        Vector(dataMap, metaMap)
      }
  }
}
