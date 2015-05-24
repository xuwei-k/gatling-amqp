Introduction
============

Gatling AMQP support

- CAUTION: This is not official library!
    - but using 'io.gatling.amqp' for FQCN to deal with 'private[gatling]', sorry.
- inspired by https://github.com/fhalim/gatling-rabbitmq (thanks!)


Usage
=====

- publish (normal)


```
  implicit val amqpProtocol: AmqpProtocol = amqp
    .host("localhost")
    .port(5672)
    .auth("guest", "guest")
    .poolSize(10)

  val scn = scenario("AMQP Publishing").repeat(1000) {
    exec(
      amqp("Publish")
        .publish("q1", payload = "{foo:1}")
    )
  }

  setUp(scn.inject(rampUsers(10) over (3 seconds))).protocols(amqpProtocol)
```

- publish (with confirmation)
    - set "confirmMode()" in protocol that use DeliveryMode(2)
    - uknown bugs: confirmMode losts last reports due to early termination of StatsWriters


```
  implicit val amqpProtocol: AmqpProtocol = amqp
    .host("localhost")
    .port(5672)
    .auth("guest", "guest")
    .poolSize(10)
    .confirmMode()

  val scn = scenario("AMQP Publishing").repeat(1000) {
    exec(
      amqp("Publish")
        .publish("q1", payload = "{foo:1}")
    )
  }

  setUp(scn.inject(rampUsers(10) over (3 seconds))).protocols(amqpProtocol)
```

- full code: src/test/scala/io/gatling/amqp/PublishingSimulation.scala


Run
===

```
% sbt
> test
```

Restrictions
============

- work in progress
    - currently only one action can be defined in action builder


Environment
===========

- gatling-sbt-2.1.6 (to implement easily)
- gatling-2.2.0-M3 (live with edge)


TODO
====

- declare exchanges, queues and bindings in protocol builder context
- declare exchanges, queues and bindings in action builder context (to test declaration costs)
- make AmqpConfig immutable
- make Builder mutable
