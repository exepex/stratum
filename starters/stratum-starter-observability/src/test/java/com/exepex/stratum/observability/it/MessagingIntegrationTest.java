package com.exepex.stratum.observability.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.core.logging.LoggingContextConstants;
import com.exepex.stratum.observability.testsupport.BaggageTestData;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Tracer;
import jakarta.jms.JMSException;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    classes = OrderEventsApplication.class,
    properties = {
      "spring.main.web-application-type=none",
      "management.tracing.sampling.probability=1.0",
      // Stratum's startup check needs an address before @DynamicPropertySource runs; the
      // collector stub's address then replaces this one.
      "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:1/v1/traces",
      "management.otlp.metrics.export.enabled=false",
      "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
      "spring.kafka.consumer.auto-offset-reset=earliest",
      "spring.jms.template.receive-timeout=30s"
    })
// Closes the application after this class, so its tracing and threads never reach the next one.
@DirtiesContext
@EmbeddedKafka(topics = OrderEvents.DESTINATION)
@DisplayName("Kafka and JMS messages in an application with the observability starter")
class MessagingIntegrationTest {

  /** Takes the spans at once, so the application closes without retrying a failed send. */
  @AutoClose private static final CollectorStub COLLECTOR = new CollectorStub();

  /**
   * Sends the spans to the collector stub. Spring Boot propagates trace headers only while span
   * export is on.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void sendSpansToCollectorStub(DynamicPropertyRegistry registry) {
    COLLECTOR.receiveSpans(registry);
  }

  /** The correlation ID the sender puts in baggage. */
  private static final String ORDER_CORRELATION_ID = "order-42-c7d1";

  /** The event sent in each test. */
  private static final String ORDER_PLACED = "order-placed";

  /** The MDC key in which Spring Boot puts the trace ID. */
  private static final String TRACE_ID = "traceId";

  /** The JMS queue that no listener reads, so the test reads the sent message itself. */
  private static final String AUDIT_QUEUE = "order-audit";

  /** Sends the order events and keeps what each receiver saw. */
  @Autowired private OrderEvents orderEvents;

  /** Starts and ends spans. */
  @Autowired private Tracer tracer;

  /** Holds the timers of the senders and receivers. */
  @Autowired private MeterRegistry meterRegistry;

  /** Spring Boot's JMS sender, which also reads the audit queue. */
  @Autowired private JmsTemplate jmsTemplate;

  /**
   * Sends an event inside a span whose baggage holds the correlation ID, and returns the trace ID.
   */
  private String sendInTrace(Consumer<String> sender) {
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
   * just after the event is handled. It uses {@code find}, whose missing timer fails the assertion
   * and is retried, because {@code get} throws an exception that Awaitility does not retry.
   */
  private void assertTimedOnce(String... timerNames) {
    await()
        .untilAsserted(
            () ->
                assertThat(timerNames)
                    .allSatisfy(
                        timerName ->
                            assertThat(meterRegistry.find(timerName).timer())
                                .as(timerName)
                                .extracting(Timer::count)
                                .isEqualTo(1L)));
  }

  @Test
  @DisplayName("a Kafka consumer joins the sender's trace, logs its correlation ID and is timed")
  void kafkaMessageCarriesTraceAndCorrelationId() throws InterruptedException {
    var traceId = sendInTrace(orderEvents::sendToKafka);

    assertReceivedInSameTrace(orderEvents.nextReceivedLoggingContext(), traceId);
    assertTimedOnce("spring.kafka.template", "spring.kafka.listener");
  }

  @Test
  @DisplayName("a JMS consumer joins the sender's trace, logs its correlation ID and is timed")
  void jmsMessageCarriesTraceAndCorrelationId() throws InterruptedException {
    var traceId = sendInTrace(orderEvents::sendToJms);

    assertReceivedInSameTrace(orderEvents.nextReceivedLoggingContext(), traceId);
    assertTimedOnce("jms.message.publish", "jms.message.process");
  }

  @Test
  @DisplayName("a JMS message carries the correlation ID and none of the sender's other baggage")
  void jmsMessageCarriesOnlyAllowedBaggage() throws JMSException {
    var span = tracer.nextSpan().name("audit-order").start();
    try (var _ = tracer.withSpan(span);
        var _ =
            tracer.createBaggageInScope(
                LoggingContextConstants.CORRELATION_ID, ORDER_CORRELATION_ID);
        var _ = tracer.createBaggageInScope("evil", "SECRET");
        var _ = tracer.createBaggageInScope("CORRELATIONID", "leak")) {
      jmsTemplate.convertAndSend(AUDIT_QUEUE, ORDER_PLACED);
    } finally {
      span.end();
    }

    var auditMessage = Objects.requireNonNull(jmsTemplate.receive(AUDIT_QUEUE));
    assertThat(auditMessage.getStringProperty(BaggageTestData.BAGGAGE_HEADER))
        .isEqualTo(BaggageTestData.CORRELATION_ID_ENTRY);
  }
}
