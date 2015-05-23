package io.gatling.amqp.action

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import io.gatling.amqp.config._
import io.gatling.amqp.data._
import io.gatling.core.action.Chainable
import io.gatling.core.session.Session
import io.gatling.core.structure.ScenarioContext
import io.gatling.core.util.TimeHelper.nowMillis

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Failure

class PublishAction(val next: ActorRef, ctx: ScenarioContext, req: PublishRequest)(implicit amqp: AmqpProtocol) extends Chainable with ActorLogging {
  override def execute(session: Session) {
    amqp.router ! InternalPublishRequest(req, ctx, session)
    next ! session
  }
}
