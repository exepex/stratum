package com.exepex.stratum.observability.it;

import com.exepex.stratum.observability.testsupport.MessageTraceChecks;
import com.exepex.stratum.observability.testsupport.ReceivedLoggingContexts;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import java.util.List;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    classes = ServiceJmsApplication.class,
    properties = {
      "spring.main.web-application-type=none",
      "management.tracing.sampling.probability=1.0",
      // Stratum's startup check needs an address before @DynamicPropertySource runs; the
      // collector stub's address then replaces this one.
      "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:1/v1/traces",
      "management.otlp.metrics.export.enabled=false"
    })
// Closes the application after this class, so its tracing and threads never reach the next one.
@DirtiesContext
@DisplayName("JMS senders and listeners that the service sets up itself are traced and timed")
class ServiceJmsIntegrationTest {

  /** Takes the spans at once, so the application closes without retrying a failed send. */
  @AutoClose private static final CollectorStub COLLECTOR = new CollectorStub();

  /** The queues of the service's own listener container and of its own listener factory. */
  private static final List<String> SERVICE_LISTENER_QUEUES =
      List.of(ServiceJmsApplication.CONTAINER_QUEUE, ServiceJmsApplication.FACTORY_QUEUE);

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

  /** The service's own sender. */
  @Autowired private JmsTemplate invoiceSender;

  /** What each of the service's receivers saw. */
  @Autowired private ReceivedLoggingContexts receivedLoggingContexts;

  /** Starts and ends spans. */
  @Autowired private Tracer tracer;

  /** Holds the timers of the senders and receivers. */
  @Autowired private MeterRegistry meterRegistry;

  /** Sends inside a trace and checks the receiver's trace and the timers. */
  private MessageTraceChecks checks;

  /** Builds the checks on the application's tracer and timers. */
  @BeforeEach
  void createChecks() {
    checks = new MessageTraceChecks(tracer, meterRegistry);
  }

  @ParameterizedTest(name = "listener on {0}")
  @FieldSource("SERVICE_LISTENER_QUEUES")
  @DisplayName(
      "a message sent by the service's own sender joins the receiver to the sender's trace, and"
          + " both are timed")
  void serviceJmsBeansAreObserved(String queue) throws InterruptedException {
    var publishedBefore = checks.timerCount(MessageTraceChecks.JMS_PUBLISH_TIMER);
    var processedBefore = checks.timerCount(MessageTraceChecks.JMS_PROCESS_TIMER);

    var traceId = checks.sendInTrace(invoice -> invoiceSender.convertAndSend(queue, invoice));

    checks.assertReceivedInSameTrace(receivedLoggingContexts.next(), traceId);
    checks.assertTimerCount(MessageTraceChecks.JMS_PUBLISH_TIMER, publishedBefore + 1);
    checks.assertTimerCount(MessageTraceChecks.JMS_PROCESS_TIMER, processedBefore + 1);
  }
}
