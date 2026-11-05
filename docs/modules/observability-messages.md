# Observability: messages

This page belongs to the [observability starter](../../starters/stratum-starter-observability/README.md).

With the module on, sending and handling a message are observed like an HTTP call:

- **Traces.** The sender adds the W3C `traceparent` and `baggage` headers to the message. The
  consumer reads them, so its span is part of the sender's trace, and its log lines carry the
  sender's trace ID and [correlation ID](observability-traces.md#correlation-id).
- **Timings.** Each send and each handled message is timed, as a [histogram](../../starters/stratum-starter-observability/README.md#timings) like every
  other timing.
- **Errors.** A Kafka or RabbitMQ listener that throws records its timing with the `error` tag set
  to an exception's class name: on Kafka the listener's own exception, such as
  `IllegalStateException`; on RabbitMQ Spring's wrapper, `ListenerExecutionFailedException`. Every
  redelivery is counted, Kafka's error handler retries included. With Spring Boot's RabbitMQ
  listener retry on (the `spring.rabbitmq.listener.simple.retry.*` keys), the attempts inside one
  delivery are timed once. A message handled without error has `error=none`. JMS timers do not show
  failures: a `@JmsListener` that throws is still recorded with `error=none`, so count JMS failures
  from your logs.
- **Kafka batch listeners.** Spring Kafka observes listeners that take one record at a time. A
  `@KafkaListener` with `batch = "true"` does not join the sender's trace and has no correlation ID,
  and its `spring.kafka.listener` timer carries Spring Kafka's older tags, `name`, `result` and
  `exception`, instead of the ones below. Spring Kafka can observe each record of a batch, if you
  turn on `setRecordObservationsInBatch(true)` on the listener container's properties.

| Technology | Timers | How it is switched on |
| --- | --- | --- |
| Kafka (`spring-kafka`) | `spring.kafka.template` (send), `spring.kafka.listener` (handle) | Stratum sets `spring.kafka.template.observation-enabled` and `spring.kafka.listener.observation-enabled` to `true`; Spring Boot leaves them off |
| JMS, such as IBM MQ or ActiveMQ | `jms.message.publish` (send), `jms.message.process` (handle) | On by default: Spring Boot observes the JMS beans it sets up, and Stratum the ones your service sets up ([below](#jms-senders-and-listeners-you-set-up)) |
| RabbitMQ (`spring-rabbit`) | `spring.rabbit.template` (send), `spring.rabbit.listener` (handle) | Stratum sets `spring.rabbitmq.template.observation-enabled` and `spring.rabbitmq.listener.{simple,direct,stream}.observation-enabled` to `true`; Spring Boot leaves them off |

Kafka and RabbitMQ timers carry where the message went or came from: Kafka sends carry the topic in
`messaging.destination.name` and Kafka listeners in `messaging.source.name`; RabbitMQ sends carry
the exchange and RabbitMQ listeners the queue, both in `messaging.destination.name`. JMS timers do
not: Micrometer keeps the JMS queue name on the span only. No timer carries a message
ID or a Kafka record key.

Spring also tags the RabbitMQ send timer, `spring.rabbit.template`, with the message's routing key
(`messaging.rabbitmq.destination.routing_key`). A routing key can hold an ID, such as
`order.42.created`, and each value would make its own time series, so the [tag guard](../../starters/stratum-starter-observability/README.md#tag-guard)
always removes that tag. Sends are still timed per destination, just not per routing key.

This works with the senders and listeners Spring Boot sets up, such as `KafkaTemplate`,
`@KafkaListener`, `JmsTemplate` and `@JmsListener`, and with the JMS ones your service sets up
itself (below). This starter adds no messaging library, so Stratum's switches do nothing until
your service brings Kafka or RabbitMQ.

To turn one off, set its switch to `false`, for example in `application.properties`:

```properties
spring.kafka.listener.observation-enabled=false
```

or in `application.yml`:

```yaml
spring:
  kafka:
    listener:
      observation-enabled: false
```

To turn them all off, set each of the six Kafka and RabbitMQ switches in the table above to
`false`.

## JMS senders and listeners you set up

Spring Boot observes only the JMS beans it builds itself. It builds no `JmsTemplate` when your
service declares its own or has two connection factories, as a service with two brokers does.
Stratum gives the same observation to the JMS objects your service sets up, with nothing to write:

- every `JmsTemplate` bean, and so every `JmsClient.create(jmsTemplate)` and
  `new JmsMessagingTemplate(jmsTemplate)` built on it;
- every listener container bean, such as a `DefaultMessageListenerContainer`;
- every `@JmsListener`, whatever listener container factory builds it, including one made with
  `new DefaultJmsListenerContainerFactory()`.

Each is timed and traced like Spring Boot's own, and each message it sends carries the trace headers
as JMS properties (`traceparent`, and `baggage` when there is any), whatever
`stratum.backward-compatible` is set to. An object on which your service set its own observation
registry keeps it: set `ObservationRegistry.NOOP` on a template, a container or a listener container
factory to leave it unobserved.

These stay unobserved, because Stratum cannot reach them:

- a template or container made with `new` and never declared as a bean, and the template inside
  `JmsClient.create(connectionFactory)` or `new JmsMessagingTemplate(connectionFactory)`; declare a
  `JmsTemplate` bean and pass it in instead;
- a `@JmsListener` registered after the application has started;
- every JMS object, when the application has more than one `ObservationRegistry` bean.
