package io.gatling.amqp

import io.gatling.amqp.Predef._
import io.gatling.amqp.config._
import io.gatling.amqp.data._
import io.gatling.core.Predef._

import scala.concurrent.duration._

class PublishingSimulation extends Simulation {
  implicit val amqpProtocol: AmqpProtocol = amqp
    .host("amqp")
    .port(5672)
    .auth("guest", "guest")
    .poolSize(10)
    .confirmMode()
    // .prepare(DeclareQueue("q1", autoDelete = false)) // TODO: implement this dsl

  val bytes = Array.fill[Byte](1024)(1) // 1KB data for test
  val req   = PublishRequest("q1", bytes = bytes).persistent

  val publish = exec(repeat(1000) { exec(amqp("Publish").publish(req)) })

  val scn = scenario("AMQP Publish(ack)").exec(publish)

  setUp(scn.inject(rampUsers(10) over (1 seconds))).protocols(amqpProtocol)
}


