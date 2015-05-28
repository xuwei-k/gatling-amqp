package io.gatling.amqp.action

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import io.gatling.amqp.config._
import io.gatling.amqp.data._
import io.gatling.amqp.infra.Logging
import io.gatling.core.action.Chainable
import io.gatling.core.result.writer.StatsEngine
import io.gatling.core.session.Session
import io.gatling.core.structure.ScenarioContext
import io.gatling.core.util.TimeHelper.nowMillis
import io.gatling.core.result.message.{KO, OK, ResponseTimings, Status}
import pl.project13.scala.rainbow._
import java.lang.Throwable

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Failure

class AmqpPublishAction(req: PublishRequest, tracker: ActorRef, val statsEngine: StatsEngine, val next: ActorRef)(implicit amqp: AmqpProtocol) extends Chainable with Logging {

  class AmqpTrackedPublishEvent(val req: PublishRequest, startedAt: Long, session: Session) extends AmqpPublishEvent {
    def onProcess(id: Int): Unit = {
      log.debug("event.onProcess".red)
      tracker ! MessageSent(msgId(id), startedAt, nowMillis, session, next, "req")
    }

    def onSuccess(id: Int): Unit = {
      log.debug("event.onSuccess".red)
      tracker ! MessageReceived(msgId(id), nowMillis, null)
    }

    def onFailure(id: Int, e: Throwable): Unit = {
      log.warning("event.onFailure".red)
      tracker ! MessageReceived(msgId(id), nowMillis, null)
    }

    private def msgId(id: Int): String = s"${session.userId}-$id"
  }

  override def execute(session: Session): Unit = {
    amqp.router ! new AmqpTrackedPublishEvent(req, nowMillis, session)
    next ! session
  }
}
