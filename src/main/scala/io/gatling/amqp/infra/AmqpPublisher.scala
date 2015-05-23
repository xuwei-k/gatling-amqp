package io.gatling.amqp.infra

import com.rabbitmq.client._
import io.gatling.amqp.config._
import io.gatling.amqp.data._
import io.gatling.core.util.TimeHelper.nowMillis
import io.gatling.core.result.message.{KO, OK, ResponseTimings, Status}
import io.gatling.core.result.writer.StatsEngine
import io.gatling.core.session.Session
import pl.project13.scala.rainbow._
import scala.collection.mutable.BitSet
import scala.collection.mutable.OpenHashMap

class AmqpPublisher(statsEngine: StatsEngine)(implicit amqp: AmqpProtocol) extends AmqpActor {
  case class PublishInfo(no: Long, startedAt: Long, session: Session)

  class PublishStats() {
    private val bit = BitSet()                         // PublishNo
    private val map = OpenHashMap[Int, PublishInfo]()  // PublishNo -> PublishInfo

    def publish(info: PublishInfo): Unit = {
      bit.add(info.no.toInt)
      map.put(info.no.toInt, info)
    }

    def consumeUntil(n: Long, callback: PublishInfo => Unit): Unit = {
      bit.takeWhile(_ <= n.toInt).foreach(n => consume(n, callback))
    }

    def consume(n: Long, callback: PublishInfo => Unit): Unit = {
      val info = map.getOrElse(n.toInt, throw new RuntimeException(s"[BUG] key($n) exists in bit, bot not found in map"))
      callback(info)
      bit.remove(n.toInt)
      map.remove(n.toInt)
    }
  }

  private val publishStats = new PublishStats()

  override def preStart(): Unit = {
    super.preStart()
    if (amqp.isConfirmMode) {
      channel.addConfirmListener(new ConfirmListener() {
        def handleAck (no: Long, multiple: Boolean): Unit = self ! PublishAcked (no, multiple)
        def handleNack(no: Long, multiple: Boolean): Unit = self ! PublishNacked(no, multiple)
      })

      channel.confirmSelect()
    }
  }

  override def postStop(): Unit = {
    super.postStop()
  }

  override def receive = {
    case msg@ PublishAcked(no, multiple) =>
      val stoppedAt = nowMillis
      val log: PublishInfo => Unit = info => logOk(info, stoppedAt)
      if (multiple)
        publishStats.consumeUntil(no, log)
      else
        publishStats.consume(no, log)

    case msg@ PublishNacked(no, multiple) =>
      val stoppedAt = nowMillis
      val log: PublishInfo => Unit = info => logNg(info, stoppedAt, s"Publish(${info.no}) nacked")
      if (multiple)
        publishStats.consumeUntil(no, log)
      else
        publishStats.consume(no, log)

    case msg@ PublishRequest(ex, routingKey, props, payload) =>
      log.debug(s"PublishRequest(${ex.name}, $routingKey)")
      super.interact(msg) { ch =>
        ch.basicPublish(ex.name, routingKey, props, payload)
      }

    case msg@ InternalPublishRequest(PublishRequest(ex, routingKey, props, payload), ctx, session) =>
      log.debug(s"InternalPublishRequest")
      val info = PublishInfo(channel.getNextPublishSeqNo(), nowMillis, session)
      try {
        channel.basicPublish(ex.name, routingKey, props, payload)
        publishStats.publish(info)

        if (! amqp.isConfirmMode) {
          logOk(info, nowMillis)
        }

      } catch {
        case e: Exception =>
          log.error(s"basicPublish($ex) failed", e)
          logNg(info, nowMillis, "publish failed")
      }
  }

  private def logOk(info: PublishInfo, stoppedAt: Long)             : Unit = logResponse(info, stoppedAt, OK, None)
  private def logNg(info: PublishInfo, stoppedAt: Long, mes: String): Unit = logResponse(info, stoppedAt, KO, Some(mes))

  private def logResponse(info: PublishInfo, stoppedAt: Long, status: Status, errorMessage: Option[String]): Unit = {
    val timings = ResponseTimings(info.startedAt, stoppedAt, stoppedAt, stoppedAt)
    val requestName = "AMQP Publish"

    val sec = (stoppedAt - info.startedAt)/1000.0
    log.debug(s"$toString: timings=$timings ($sec)")
    statsEngine.logResponse(info.session, requestName, timings, status, None, errorMessage)
  }
}
