package com.exepex.stratum.observability.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.logging.LoggingContextConstants;
import com.exepex.stratum.observability.testsupport.BaggageTestData;
import com.exepex.stratum.observability.testsupport.MessageTraceChecks;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.tracing.Tracer;
import jakarta.jms.JMSException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
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
      "spring.kafka.consumer.auto-offset-reset=earliest"
    })
// Closes the application after this class, so its tracing and threads never reach the next one.
@DirtiesContext
@EmbeddedKafka(topics = {OrderEvents.DESTINATION, OrderEvents.BATCH_DESTINATION})
@DisplayName("Kafka and JMS messages in an application with the observability starter")
class MessagingIntegrationTest {

  /** The JMS queue that no listener reads, so the test reads the sent message itself. */
  private static final String AUDIT_QUEUE = "order-audit";

  /** Spring Kafka's timer of each sent record. */
  private static final String KAFKA_SEND_TIMER = "spring.kafka.template";

  /** Spring Kafka's timer of each handled record or batch. */
  private static final String KAFKA_LISTENER_TIMER = "spring.kafka.listener";

  /** The tag of a Kafka listener's timer that names the topic it reads. */
  private static final String KAFKA_SOURCE_TAG = "messaging.source.name";

  /** The tags of a Kafka listener's timer, whether it takes one record or a batch. */
  private static final Set<String> KAFKA_LISTENER_TAGS =
      Set.of(
          "error",
          "messaging.kafka.consumer.group",
          "messaging.operation",
          "messaging.source.kind",
          KAFKA_SOURCE_TAG,
          "messaging.system",
          "spring.kafka.listener.id");

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

  /** Sends the order events and keeps what each receiver saw. */
  @Autowired private OrderEvents orderEvents;

  /** Starts and ends spans. */
  @Autowired private Tracer tracer;

  /** Holds the timers of the senders and receivers. */
  @Autowired private MeterRegistry meterRegistry;

  /** Spring Boot's JMS sender, which also reads the audit queue. */
  @Autowired private JmsTemplate jmsTemplate;

  /** Sends inside a trace and checks the receiver's trace and the timers. */
  private MessageTraceChecks checks;

  /** Builds the checks on the application's tracer and timers. */
  @BeforeEach
  void createChecks() {
    checks = new MessageTraceChecks(tracer, meterRegistry);
  }

  /** Waits until the timers with these names each recorded one duration. */
  private void assertTimedOnce(String... timerNames) {
    Arrays.stream(timerNames).forEach(timerName -> checks.assertTimerCount(timerName, 1));
  }

  /** Waits until the send to this topic was timed once and its handling this many times. */
  private void assertKafkaTimed(String topic, long handledCount) {
    checks.assertTimerCount(KAFKA_SEND_TIMER, 1, Tag.of("messaging.destination.name", topic));
    checks.assertTimerCount(KAFKA_LISTENER_TIMER, handledCount, Tag.of(KAFKA_SOURCE_TAG, topic));
  }

  @Test
  @DisplayName("a Kafka consumer joins the sender's trace, logs its correlation ID and is timed")
  void kafkaMessageCarriesTraceAndCorrelationId() throws InterruptedException {
    var traceId = checks.sendInTrace(orderEvents::sendToKafka);

    checks.assertReceivedInSameTrace(orderEvents.getReceivedLoggingContexts().next(), traceId);
    assertKafkaTimed(OrderEvents.DESTINATION, 1);
  }

  @Test
  @DisplayName(
      "a Kafka batch listener joins its first record's trace and is timed twice per batch, with"
          + " the tags of a single-record listener")
  void kafkaBatchCarriesFirstRecordTrace() throws InterruptedException {
    var traceId = checks.sendInTrace(orderEvents::sendToKafkaBatch);

    checks.assertReceivedInSameTrace(orderEvents.getReceivedLoggingContexts().next(), traceId);
    // Spring Kafka 4.2.0 stops a batch's observation twice, in the listener adapter and in the
    // container, so one batch counts 2; the module page says so.
    assertKafkaTimed(OrderEvents.BATCH_DESTINATION, 2);
    var batchTimer =
        meterRegistry
            .get(KAFKA_LISTENER_TIMER)
            .tag(KAFKA_SOURCE_TAG, OrderEvents.BATCH_DESTINATION)
            .timer();
    assertThat(batchTimer.getId().getTags())
        .extracting(Tag::getKey)
        .containsExactlyInAnyOrderElementsOf(KAFKA_LISTENER_TAGS);
  }

  @Test
  @DisplayName("a JMS consumer joins the sender's trace, logs its correlation ID and is timed")
  void jmsMessageCarriesTraceAndCorrelationId() throws InterruptedException {
    var traceId = checks.sendInTrace(orderEvents::sendToJms);

    checks.assertReceivedInSameTrace(orderEvents.getReceivedLoggingContexts().next(), traceId);
    assertTimedOnce(MessageTraceChecks.JMS_PUBLISH_TIMER, MessageTraceChecks.JMS_PROCESS_TIMER);
  }

  @Test
  @DisplayName("a JMS message carries the correlation ID and none of the sender's other baggage")
  void jmsMessageCarriesOnlyAllowedBaggage() throws JMSException {
    var span = tracer.nextSpan().name("audit-order").start();
    try (var _ = tracer.withSpan(span);
        var _ =
            tracer.createBaggageInScope(
                LoggingContextConstants.CORRELATION_ID, MessageTraceChecks.ORDER_CORRELATION_ID);
        var _ = tracer.createBaggageInScope("evil", "SECRET");
        var _ = tracer.createBaggageInScope("CORRELATIONID", "leak")) {
      jmsTemplate.convertAndSend(AUDIT_QUEUE, MessageTraceChecks.ORDER_PLACED);
    } finally {
      span.end();
    }

    var auditMessage = Objects.requireNonNull(jmsTemplate.receive(AUDIT_QUEUE));
    assertThat(auditMessage.getStringProperty(BaggageTestData.BAGGAGE_HEADER))
        .isEqualTo(BaggageTestData.CORRELATION_ID_ENTRY);
  }
}
