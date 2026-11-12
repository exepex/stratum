package com.exepex.stratum.observability.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.logging.LoggingContextConstants;
import com.exepex.stratum.observability.testsupport.BaggageTestData;
import com.exepex.stratum.observability.testsupport.MessageTraceChecks;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import jakarta.jms.JMSException;
import java.util.Arrays;
import java.util.Objects;
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
@EmbeddedKafka(topics = OrderEvents.DESTINATION)
@DisplayName("Kafka and JMS messages in an application with the observability starter")
class MessagingIntegrationTest {

  /** The JMS queue that no listener reads, so the test reads the sent message itself. */
  private static final String AUDIT_QUEUE = "order-audit";

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

  @Test
  @DisplayName("a Kafka consumer joins the sender's trace, logs its correlation ID and is timed")
  void kafkaMessageCarriesTraceAndCorrelationId() throws InterruptedException {
    var traceId = checks.sendInTrace(orderEvents::sendToKafka);

    checks.assertReceivedInSameTrace(orderEvents.getReceivedLoggingContexts().next(), traceId);
    assertTimedOnce("spring.kafka.template", "spring.kafka.listener");
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
