package io.gatling.amqp.infra

import akka.actor._
import com.rabbitmq.client._
import com.rabbitmq.client.QueueingConsumer.Delivery
import io.gatling.amqp.config._
import io.gatling.amqp.data._
import io.gatling.amqp.event._
import io.gatling.amqp.util._
import io.gatling.core.util.TimeHelper.nowMillis
import io.gatling.core.session.Session
import java.util.concurrent.atomic._
import scala.collection.mutable
import scala.concurrent.duration._
import scala.util._
import pl.project13.scala.rainbow._

class AmqpConsumer(actorName: String, session: Session)(implicit _amqp: AmqpProtocol) extends AmqpActor with Stats {
  implicit val amqp: AmqpProtocol = _amqp

  private val checkTerminationInterval = 1.second

  private var _consumer: Option[QueueingConsumer] = None
  private def consumer = _consumer.getOrElse{ throw new RuntimeException("[bug] consumer is not defined yet") }

  private var lastRequestedAt: Long = 0
  private var lastDeliveredAt: Long = 0
  private val runningMarginMsec = 2000  // assumes that no messages queued when this time past from lastDeliveredAt

  case class Delivered(startedAt: Long, stoppedAt: Long, delivery: Delivery)
  case class DeliveryTimeouted(msec: Long) extends RuntimeException
  private val deliveryTimeout = 1000  // msec

  override def preStart(): Unit = {
    super.preStart()
    _consumer = Some(new QueueingConsumer(channel))
  }

  private case class ConsumeRequested()
  private case class BlockingReadOne()

  private def isFinished: Boolean = (lastRequestedAt > 0) && (lastDeliveredAt + runningMarginMsec < nowMillis)

  private val terminationWaitingActors = mutable.HashMap[ActorRef, Any]()
  private def waitTermination(ref: ActorRef, mes: Any) = terminationWaitingActors.put(ref, mes)
  private def startCheckTerminationOnce(): Unit = {
    if (terminationWaitingActors.isEmpty)
      self ! CheckTermination(checkTerminationInterval)
  }

  private def notifyTermination(msg: String): Unit = {
    terminationWaitingActors.foreach{ case (ref, mes) => ref ! Success(msg) }
    terminationWaitingActors.clear
  }

  private case class CheckTermination(interval: FiniteDuration)

  override def receive = {
    case mes@ WaitTermination(session) =>      
      startCheckTerminationOnce()
      waitTermination(sender(), mes)

    case mes@ CheckTermination(interval) =>
      isFinished match {
        case true =>
          val msg = "all message have been delivered"
          log.debug(msg.green)
          notifyTermination(msg)
          context.stop(self)  // ignore all rest messages
        case false =>
          log.debug(s"CheckTermination: waiting delivered. re-check after($interval)".yellow)
          context.system.scheduler.scheduleOnce(interval, self, mes)  // retry again after interval
      }

    case BlockingReadOne() =>
      tryNextDelivery(deliveryTimeout) match {
        case Success(delivered: Delivered)    => deliveryFound(delivered)
        case Failure(DeliveryTimeouted(msec)) => deliveryTimeouted(msec)
        case Failure(error)                   => deliveryFailed(error)
      }
      self ! BlockingReadOne()

    case AmqpConsumeRequest(req, session) =>
      if (req.autoAck)
        consumeSync(req.queue)
      else
        consumeAsync(req)
  }

  protected def consumeSync(queueName: String): Unit = {
    channel.basicConsume(queueName, true, consumer)
    self ! BlockingReadOne()
  }

  protected def tryNextDelivery(timeoutMsec: Long): Try[Delivered] = Try {
    lastRequestedAt = nowMillis
    val delivery: Delivery = consumer.nextDelivery(timeoutMsec)
    if (delivery == null) {
      throw DeliveryTimeouted(timeoutMsec)
    }

    lastDeliveredAt = nowMillis
    Success(Delivered(lastRequestedAt, lastDeliveredAt, delivery))
  }.flatten

  protected def consumeAsync(req: ConsumeRequest): Unit = {
    ???
  }

  protected def deliveryTimeouted(msec: Long): Unit = {
    log.debug(s"$actorName delivery timeouted($msec)")
  }
  
  protected def deliveryFailed(err: Throwable): Unit = {
    log.warn(s"$actorName delivery failed: $err".yellow)
  }
  
  protected def deliveryFound(delivered: Delivered): Unit = {
//    val message = new String(delivery.getBody())
    import delivered._
    val tag = delivery.getEnvelope.getDeliveryTag
    statsOk(session, startedAt, stoppedAt, "consume")
    log.debug(s"$actorName.consumeSync: got $tag".red)
  }
}
