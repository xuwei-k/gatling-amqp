package io.gatling.amqp.data

import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client.MessageProperties
import io.gatling.core.session.Session
import io.gatling.core.structure._
import java.lang.Throwable

trait AmqpPublishEvent {
  val req: PublishRequest
  def onProcess(id: Int): Unit
  def onSuccess(id: Int): Unit
  def onFailure(id: Int, e: Throwable): Unit
}
