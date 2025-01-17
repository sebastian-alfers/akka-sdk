/*
 * Copyright (C) 2021-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.javasdk.impl.eventsourcedentity

import scala.util.control.NonFatal
import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Source
import com.google.protobuf.ByteString
import com.google.protobuf.any.{ Any => ScalaPbAny }
import io.grpc.Status
import akka.javasdk.impl.ErrorHandling.BadRequestException
import EventSourcedEntityRouter.CommandResult
import akka.annotation.InternalApi
import akka.javasdk.Metadata
import akka.javasdk.Tracing
import akka.javasdk.eventsourcedentity.CommandContext
import akka.javasdk.eventsourcedentity.EventContext
import akka.javasdk.eventsourcedentity.EventSourcedEntity
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext
import akka.javasdk.impl.AbstractContext
import akka.javasdk.impl.ActivatableContext
import akka.javasdk.impl.AnySupport
import akka.javasdk.impl.Settings
import akka.javasdk.impl.ErrorHandling
import akka.javasdk.impl.JsonMessageCodec
import akka.javasdk.impl.MetadataImpl
import akka.javasdk.impl.Service
import akka.javasdk.impl.effect.ErrorReplyImpl
import akka.javasdk.impl.effect.MessageReplyImpl
import akka.javasdk.impl.effect.SecondaryEffectImpl
import akka.javasdk.impl.telemetry.EventSourcedEntityCategory
import akka.javasdk.impl.telemetry.SpanTracingImpl
import akka.javasdk.impl.telemetry.Telemetry
import akka.javasdk.impl.telemetry.TraceInstrumentation
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import kalix.protocol.component.Failure
import kalix.protocol.event_sourced_entity.EventSourcedStreamIn.Message.{ Command => InCommand }
import kalix.protocol.event_sourced_entity.EventSourcedStreamIn.Message.{ Empty => InEmpty }
import kalix.protocol.event_sourced_entity.EventSourcedStreamIn.Message.{ Event => InEvent }
import kalix.protocol.event_sourced_entity.EventSourcedStreamIn.Message.{ Init => InInit }
import kalix.protocol.event_sourced_entity.EventSourcedStreamIn.Message.{ SnapshotRequest => InSnapshotRequest }
import kalix.protocol.event_sourced_entity.EventSourcedStreamOut.Message.{ Failure => OutFailure }
import kalix.protocol.event_sourced_entity.EventSourcedStreamOut.Message.{ Reply => OutReply }
import kalix.protocol.event_sourced_entity.EventSourcedStreamOut.Message.{ SnapshotReply => OutSnapshotReply }
import kalix.protocol.event_sourced_entity._
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * INTERNAL API
 */
@InternalApi
private[impl] final case class EventSourcedEntityService[S, E, ES <: EventSourcedEntity[S, E]](
    eventSourcedEntityClass: Class[_],
    _messageCodec: JsonMessageCodec,
    factory: EventSourcedEntityContext => ES,
    snapshotEvery: Int = 0)
    extends Service(eventSourcedEntityClass, EventSourcedEntities.name, _messageCodec) {

  def withSnapshotEvery(snapshotEvery: Int): EventSourcedEntityService[S, E, ES] =
    if (snapshotEvery != this.snapshotEvery) copy(snapshotEvery = snapshotEvery)
    else this

  def createRouter(context: EventSourcedEntityContext) =
    new ReflectiveEventSourcedEntityRouter[S, E, ES](
      factory(context),
      componentDescriptor.commandHandlers,
      messageCodec)
}

/**
 * INTERNAL API
 */
@InternalApi
private[impl] final class EventSourcedEntitiesImpl(
    system: ActorSystem,
    _services: Map[String, EventSourcedEntityService[_, _, _]],
    configuration: Settings,
    sdkDispatcherName: String,
    tracerFactory: () => Tracer)
    extends EventSourcedEntities {
  import akka.javasdk.impl.EntityExceptions._

  private val log = LoggerFactory.getLogger(this.getClass)
  private final val services = _services.iterator.map { case (name, service) =>
    if (service.snapshotEvery < 0)
      log.warn("Snapshotting disabled for entity [{}], this is not recommended.", service.componentId)
    // FIXME overlay configuration provided by _system
    (name, if (service.snapshotEvery == 0) service.withSnapshotEvery(configuration.snapshotEvery) else service)
  }.toMap

  private val instrumentations: Map[String, TraceInstrumentation] = services.values.map { s =>
    (s.componentId, new TraceInstrumentation(s.componentId, EventSourcedEntityCategory, tracerFactory))
  }.toMap

  private val pbCleanupDeletedEventSourcedEntityAfter =
    Some(com.google.protobuf.duration.Duration(configuration.cleanupDeletedEventSourcedEntityAfter))

  /**
   * The stream. One stream will be established per active entity. Once established, the first message sent will be
   * Init, which contains the entity ID, and, if the entity has previously persisted a snapshot, it will contain that
   * snapshot. It will then send zero to many event messages, one for each event previously persisted. The entity is
   * expected to apply these to its state in a deterministic fashion. Once all the events are sent, one to many commands
   * are sent, with new commands being sent as new requests for the entity come in. The entity is expected to reply to
   * each command with exactly one reply message. The entity should reply in order, and any events that the entity
   * requests to be persisted the entity should handle itself, applying them to its own state, as if they had arrived as
   * events when the event stream was being replayed on load.
   */
  override def handle(in: akka.stream.scaladsl.Source[EventSourcedStreamIn, akka.NotUsed])
      : akka.stream.scaladsl.Source[EventSourcedStreamOut, akka.NotUsed] = {
    in.prefixAndTail(1)
      .flatMapConcat {
        case (Seq(EventSourcedStreamIn(InInit(init), _)), source) =>
          source.via(runEntity(init))
        case (Seq(), _) =>
          // if error during recovery in runtime the stream will be completed before init
          log.error("Event Sourced Entity stream closed before init.")
          Source.empty[EventSourcedStreamOut]
        case (Seq(EventSourcedStreamIn(other, _)), _) =>
          throw ProtocolException(
            s"Expected init message for Event Sourced Entity, but received [${other.getClass.getName}]")
      }
      .recover { case error =>
        // only "unexpected" exceptions should end up here
        ErrorHandling.withCorrelationId { correlationId =>
          log.error(failureMessageForLog(error), error)
          toFailureOut(error, correlationId)
        }
      }
  }

  private def toFailureOut(error: Throwable, correlationId: String) = {
    error match {
      case EntityException(entityId, commandId, commandName, _, _) =>
        EventSourcedStreamOut(
          OutFailure(
            Failure(
              commandId = commandId,
              description = s"Unexpected entity [$entityId] error for command [$commandName] [$correlationId]")))
      case _ =>
        EventSourcedStreamOut(OutFailure(Failure(description = s"Unexpected error [$correlationId]")))
    }
  }

  private def runEntity(init: EventSourcedInit): Flow[EventSourcedStreamIn, EventSourcedStreamOut, NotUsed] = {
    val service =
      services.getOrElse(init.serviceName, throw ProtocolException(init, s"Service not found: ${init.serviceName}"))

    val router = service
      .createRouter(new EventSourcedEntityContextImpl(init.entityId))
      .asInstanceOf[EventSourcedEntityRouter[Any, Any, EventSourcedEntity[Any, Any]]]
    val thisEntityId = init.entityId

    val startingSequenceNumber = (for {
      snapshot <- init.snapshot
      any <- snapshot.snapshot
    } yield {
      val snapshotSequence = snapshot.snapshotSequence
      router._internalHandleSnapshot(service.messageCodec.decodeMessage(any))
      snapshotSequence
    }).getOrElse(0L)
    Flow[EventSourcedStreamIn]
      .map(_.message)
      .scan[(Long, Option[EventSourcedStreamOut.Message])]((startingSequenceNumber, None)) {
        case (_, InEvent(event)) =>
          // Note that these only come on replay
          val context = new EventContextImpl(thisEntityId, event.sequence)
          val ev =
            service.messageCodec
              .decodeMessage(event.payload.get)
              .asInstanceOf[AnyRef] // FIXME empty?
          router._internalHandleEvent(ev, context)
          (event.sequence, None)
        case ((sequence, _), InCommand(command)) =>
          if (thisEntityId != command.entityId)
            throw ProtocolException(command, "Receiving entity is not the intended recipient of command")
          val span = instrumentations(service.componentId).buildSpan(service, command)
          span.foreach(s => MDC.put(Telemetry.TRACE_ID, s.getSpanContext.getTraceId))
          try {
            val cmd =
              service.messageCodec.decodeMessage(
                command.payload.getOrElse(
                  // FIXME smuggling 0 arity method called from component client through here
                  ScalaPbAny.defaultInstance.withTypeUrl(AnySupport.JsonTypeUrlPrefix).withValue(ByteString.empty())))
            val metadata = MetadataImpl.of(command.metadata.map(_.entries.toVector).getOrElse(Nil))
            val context =
              new CommandContextImpl(thisEntityId, sequence, command.name, command.id, metadata, span, tracerFactory)

            val CommandResult(
              events: Vector[Any],
              secondaryEffect: SecondaryEffectImpl,
              snapshot: Option[Any],
              endSequenceNumber,
              deleteEntity) =
              try {
                router._internalHandleCommand(
                  command.name,
                  cmd,
                  context,
                  service.snapshotEvery,
                  seqNr => new EventContextImpl(thisEntityId, seqNr))
              } catch {
                case BadRequestException(msg) =>
                  val errorReply = ErrorReplyImpl(msg, Some(Status.Code.INVALID_ARGUMENT))
                  CommandResult(Vector.empty, errorReply, None, context.sequenceNumber, false)
                case e: EntityException =>
                  throw e
                case NonFatal(error) =>
                  throw EntityException(command, s"Unexpected failure: $error", Some(error))
              } finally {
                context.deactivate() // Very important!
              }

            val serializedSecondaryEffect = secondaryEffect match {
              case MessageReplyImpl(message, metadata) =>
                MessageReplyImpl(service.messageCodec.encodeJava(message), metadata)
              case other => other
            }

            val clientAction = serializedSecondaryEffect.replyToClientAction(
              command.id,
              None // None because we can use the one inside the SecondaryEffect
            )

            serializedSecondaryEffect match {
              case _: ErrorReplyImpl[_] => // error
                (
                  endSequenceNumber,
                  Some(OutReply(EventSourcedReply(commandId = command.id, clientAction = clientAction))))
              case _ => // non-error
                val serializedEvents =
                  events.map(event => ScalaPbAny.fromJavaProto(service.messageCodec.encodeJava(event)))
                val serializedSnapshot =
                  snapshot.map(state => ScalaPbAny.fromJavaProto(service.messageCodec.encodeJava(state)))
                val delete = if (deleteEntity) pbCleanupDeletedEventSourcedEntityAfter else None
                (
                  endSequenceNumber,
                  Some(
                    OutReply(
                      EventSourcedReply(
                        command.id,
                        clientAction,
                        Seq.empty,
                        serializedEvents,
                        serializedSnapshot,
                        delete))))
            }
          } finally {
            span.foreach { s =>
              MDC.remove(Telemetry.TRACE_ID)
              s.end()
            }
          }
        case ((sequence, _), InSnapshotRequest(request)) =>
          val reply =
            EventSourcedSnapshotReply(request.requestId, Some(service.messageCodec.encodeScala(router._stateOrEmpty())))
          (sequence, Some(OutSnapshotReply(reply)))
        case (_, InInit(_)) =>
          throw ProtocolException(init, "Entity already initiated")
        case (_, InEmpty) =>
          throw ProtocolException(init, "Received empty/unknown message")
      }
      .collect { case (_, Some(message)) =>
        EventSourcedStreamOut(message)
      }
      .recover { case error =>
        // only "unexpected" exceptions should end up here
        ErrorHandling.withCorrelationId { correlationId =>
          LoggerFactory.getLogger(router.entityClass).error(failureMessageForLog(error), error)
          toFailureOut(error, correlationId)
        }
      }
      .async(sdkDispatcherName)
  }

  private class CommandContextImpl(
      override val entityId: String,
      override val sequenceNumber: Long,
      override val commandName: String,
      override val commandId: Long,
      override val metadata: Metadata,
      span: Option[Span],
      tracerFactory: () => Tracer)
      extends AbstractContext
      with CommandContext
      with ActivatableContext {
    override def tracing(): Tracing = new SpanTracingImpl(span, tracerFactory)
  }

  private class EventSourcedEntityContextImpl(override final val entityId: String)
      extends AbstractContext
      with EventSourcedEntityContext

  private final class EventContextImpl(entityId: String, override val sequenceNumber: Long)
      extends EventSourcedEntityContextImpl(entityId)
      with EventContext
}
