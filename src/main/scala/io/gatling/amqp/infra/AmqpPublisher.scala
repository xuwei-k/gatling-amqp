package io.gatling.amqp.infra

import com.rabbitmq.client._
import io.gatling.amqp.config._
import io.gatling.amqp.data._
import io.gatling.core.util.TimeHelper.nowMillis
import io.gatling.core.result.writer.StatsEngine
import io.gatling.core.session.Session
import scala.collection.mutable.{BitSet, HashSet, OpenHashMap}
import pl.project13.scala.rainbow._

class AmqpPublisher(statsEngine: StatsEngine)(implicit amqp: AmqpProtocol) extends AmqpActor {
  class EventQueue() {
    private val bit = new BitSet()                         // PublishNo
    private val map = OpenHashMap[Int, AmqpPublishEvent]()  // PublishNo -> PublishInfo

    def publish(id: Int, event: AmqpPublishEvent): Unit = {
      event.onProcess(id)
      bit.add(id)
      map.put(id, event)
    }

    def consumeUntil(n: Int): Unit = {
      bit.takeWhile(_ <= n.toInt).foreach(consume)
    }

    def consume(n: Int): Unit = {
      val event = map.getOrElse(n.toInt, throw new RuntimeException(s"[BUG] key($n) exists in bit, bot not found in map"))
      event.onSuccess(n)
      bit.remove(n)
      map.remove(n)
    }
  }

  private val eventQueue = new EventQueue()
  private val publisherIds = new HashSet[String]()  // Session.userId.toInt

  override def preStart(): Unit = {
    super.preStart()
    if (amqp.isConfirmMode) {
      channel.addConfirmListener(new ConfirmListener() {
        def handleAck (no: Long, multiple: Boolean): Unit = self ! PublishAcked (no.toInt, multiple)
        def handleNack(no: Long, multiple: Boolean): Unit = self ! PublishNacked(no.toInt, multiple)
      })

      channel.confirmSelect()
    }
  }

  override def postStop(): Unit = {
    super.postStop()
  }

  override def receive = {
    case event: AmqpPublishEvent if amqp.isConfirmMode =>
      import event.req._
      log.debug(s"AmqpPublishEvent(${exchange.name}, $routingKey)")
      try {
        val eventId: Int = channel.getNextPublishSeqNo().toInt  // unsafe, but acceptable in realtime simulations
        eventQueue.publish(eventId, event)
        channel.basicPublish(exchange.name, routingKey, properties, payload)
      } catch {
        case e: Exception =>
          log.error(s"basicPublish($exchange) failed", e)
      }

    case msg@ PublishAcked(no, multiple) =>
      if (multiple)
        eventQueue.consumeUntil(no)
      else
        eventQueue.consume(no)

    case msg@ PublishNacked(no, multiple) =>
      if (multiple)
        eventQueue.consumeUntil(no)
      else
        eventQueue.consume(no)

    case req: PublishRequest =>
      import req._
      log.debug(s"PublishRequest(${exchange.name}, $routingKey)")
      super.interact(req) { ch =>
        ch.basicPublish(exchange.name, routingKey, properties, payload)
      }

    case WaitConfirms(publisher, session) =>
      publisherIds.remove(session.userId)
      if (publisherIds.isEmpty) {
//        log.info("got confirms: all publisher finished".green)
      } else {
//        log.info(s"got confirms: waiting publishers ${publisherIds}".yellow)
      }
  }

/*
  private def publishOne(event: InternalPublishRequest): Unit = {
    import event._
    import event.req._
    val info = PublishInfo(channel.getNextPublishSeqNo(), nowMillis, session)
    try {
      channel.basicPublish(exchange.name, routingKey, properties, payload)
      eventQueue.publish(info)
      publisherIds.add(session.userId)

      if (! amqp.isConfirmMode) {
        logOk(info, nowMillis)
      }
    } catch {
      case e: Exception =>
        log.error(s"basicPublish($exchange) failed", e)
        logNg(info, nowMillis, "publish failed")
    }
  }
 */
}
