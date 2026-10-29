package com.exepex.stratum.observability.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.core.error.LoggingContextConstants;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import java.io.IOException;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;

@EmbeddedKafka(topics = OrderEvents.DESTINATION)
@DisplayName("Kafka and JMS messages in an application with the observability starter")
class MessagingIntegrationTest {

  /** The correlation ID the sender puts in baggage. */
  private static final String ORDER_CORRELATION_ID = "order-42-c7d1";

  /** The event sent in each test. */
  private static final String ORDER_PLACED = "order-placed";

  /** The MDC key in which Spring Boot puts the trace ID. */
  private static final String TRACE_ID = "traceId";

  /** Receives the spans and metrics the application sends. */
  private final CollectorStub collector = new CollectorStub();

  /**
   * Starts the collector stub for one test.
   *
   * @throws IOException if no local port is free
   */
  MessagingIntegrationTest() throws IOException {}

  /** Stops the collector stub after each test. */
  @AfterEach
  void stopCollector() {
    collector.close();
  }

  /** Starts the test application with the module on and Kafka at the embedded broker. */
  private ConfigurableApplicationContext start(EmbeddedKafkaBroker kafkaBroker) {
    return new SpringApplicationBuilder(OrderEventsApplication.class)
        .web(WebApplicationType.NONE)
        .run(
            "--stratum.observability.enabled=true",
            "--management.otlp.metrics.export.url=" + collector.url(CollectorStub.METRICS_PATH),
            "--management.opentelemetry.tracing.export.otlp.endpoint="
                + collector.url(CollectorStub.TRACES_PATH),
            "--management.tracing.sampling.probability=1.0",
            "--spring.kafka.bootstrap-servers=" + kafkaBroker.getBrokersAsString(),
            "--spring.kafka.consumer.auto-offset-reset=earliest");
  }

  /**
   * Sends an event inside a span whose baggage holds the correlation ID, and returns the trace ID.
   */
  private String sendInTrace(ConfigurableApplicationContext context, Consumer<String> sender) {
    var tracer = context.getBean(Tracer.class);
    var span = tracer.nextSpan().name("place-order").start();
    try (var _ = tracer.withSpan(span);
        var _ =
            tracer.createBaggageInScope(
                LoggingContextConstants.CORRELATION_ID, ORDER_CORRELATION_ID)) {
      sender.accept(ORDER_PLACED);
      return span.context().traceId();
    } finally {
      span.end();
    }
  }

  /** Checks that the receiver logged with the sender's trace ID and correlation ID. */
  private void assertReceivedInSameTrace(
      @Nullable Map<String, String> loggingContext, String traceId) {
    assertThat(loggingContext)
        .containsEntry(TRACE_ID, traceId)
        .containsEntry(LoggingContextConstants.CORRELATION_ID, ORDER_CORRELATION_ID);
  }

  /**
   * Waits until the timers with these names each recorded one duration; a receiver's timer stops
   * just after the event is handled.
   */
  private void assertTimedOnce(ConfigurableApplicationContext context, String... timerNames) {
    var meterRegistry = context.getBean(MeterRegistry.class);
    await()
        .untilAsserted(
            () ->
                assertThat(timerNames)
                    .allSatisfy(
                        timerName ->
                            assertThat(meterRegistry.get(timerName).timer().count()).isOne()));
  }

  @Test
  @DisplayName("a Kafka consumer joins the sender's trace, logs its correlation ID and is timed")
  void kafkaMessageCarriesTraceAndCorrelationId(EmbeddedKafkaBroker kafkaBroker)
      throws InterruptedException {
    try (var context = start(kafkaBroker)) {
      var orderEvents = context.getBean(OrderEvents.class);
      var traceId = sendInTrace(context, orderEvents::sendToKafka);

      assertReceivedInSameTrace(orderEvents.nextReceivedLoggingContext(), traceId);
      assertTimedOnce(context, "spring.kafka.template", "spring.kafka.listener");
    }
  }

  @Test
  @DisplayName("a JMS consumer joins the sender's trace, logs its correlation ID and is timed")
  void jmsMessageCarriesTraceAndCorrelationId(EmbeddedKafkaBroker kafkaBroker)
      throws InterruptedException {
    try (var context = start(kafkaBroker)) {
      var orderEvents = context.getBean(OrderEvents.class);
      var traceId = sendInTrace(context, orderEvents::sendToJms);

      assertReceivedInSameTrace(orderEvents.nextReceivedLoggingContext(), traceId);
      assertTimedOnce(context, "jms.message.publish", "jms.message.process");
    }
  }
}
