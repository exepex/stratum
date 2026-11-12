package com.exepex.stratum.observability.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.observability.testsupport.MessageTraceChecks;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
    classes = FailingJmsListenersApplication.class,
    properties = {
      "spring.main.web-application-type=none",
      "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:1/v1/traces",
      "management.tracing.export.enabled=false",
      "management.otlp.metrics.export.enabled=false"
    })
// Closes the application after this class, so its listeners never reach the next one.
@DirtiesContext
@DisplayName("A JMS listener that fails is recorded as an error on its timer")
class JmsListenerErrorsIntegrationTest {

  /** The tag in which the timer names the failure's class, or {@code none}. */
  private static final String ERROR_TAG = "error";

  /**
   * Each failing listener's queue, with the class its timer records: Spring's wrapper for a
   * {@code @JmsListener}, the listener's own exception for a plain listener.
   */
  private static final List<Arguments> FAILING_LISTENERS =
      List.of(
          Arguments.of(
              FailingJmsListenersApplication.FAILING_ANNOTATED_QUEUE,
              "ListenerExecutionFailedException"),
          Arguments.of(
              FailingJmsListenersApplication.FAILING_CONTAINER_QUEUE, "IllegalStateException"));

  /** Spring Boot's sender. */
  @Autowired private JmsTemplate jmsTemplate;

  /** Holds the listeners' timers. */
  @Autowired private MeterRegistry meterRegistry;

  /** Waits until the handling timer has recorded a message with this error tag. */
  private void assertProcessedWithError(String errorTagValue) {
    await()
        .untilAsserted(
            () ->
                assertThat(
                        meterRegistry
                            .find(MessageTraceChecks.JMS_PROCESS_TIMER)
                            .tag(ERROR_TAG, errorTagValue)
                            .timer())
                    .as(errorTagValue)
                    .isNotNull());
  }

  @ParameterizedTest(name = "{0} records {1}")
  @FieldSource("FAILING_LISTENERS")
  @DisplayName("a listener that throws records its failure's class")
  void failingListenerRecordsError(String queue, String errorClass) {
    jmsTemplate.convertAndSend(queue, "refund-17");

    assertProcessedWithError(errorClass);
  }

  @Test
  @DisplayName("a listener that succeeds records no error")
  void succeedingListenerRecordsNone() {
    jmsTemplate.convertAndSend(FailingJmsListenersApplication.SUCCEEDING_QUEUE, "refund-18");

    assertProcessedWithError("none");
  }
}
