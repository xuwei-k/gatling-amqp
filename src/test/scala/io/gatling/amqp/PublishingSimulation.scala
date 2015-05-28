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
    .poolSize(3)
    .confirmMode()
    // .prepare(DeclareQueue("q1", autoDelete = false)) // TODO: implement this dsl

  val bytes    = Array.fill[Byte](1024)(1) // 1KB data for test
  val write1kb = PublishRequest("q1", bytes = bytes)
  // val req   = write1kb.persistent
  // val req   = write1kb.repeat(1000)
  // val req   = write1kb.confirm
  val req      = write1kb.persistent.repeat(1000).confirm

//  val d = amqp("pueue").queue("q1").publish(bytes)

  val scn = scenario("AMQP Publish(ack)").repeat(1000){
    exec(amqp("Publish").publish(req))
  }.pause(1)

  setUp(scn.inject(rampUsers(3) over (1 seconds))).protocols(amqpProtocol)
}


