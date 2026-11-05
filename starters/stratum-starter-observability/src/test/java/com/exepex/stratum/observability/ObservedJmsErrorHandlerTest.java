package com.exepex.stratum.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.exepex.stratum.test.LogLineCapture;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@DisplayName("ObservedJmsErrorHandler records a failed message on its observation and logs it")
class ObservedJmsErrorHandlerTest {

  /** The logger the container would log through. */
  private static final String CONTAINER_LOGGER = "com.example.orders.OrderListenerContainer";

  /** The listener's failure. */
  private final IllegalStateException listenerError =
      new IllegalStateException("order 42 could not be stored");

  /** A registry that keeps every observation, as Spring Boot's does with metrics and tracing on. */
  private final ObservationRegistry observationRegistry = ObservationRegistry.create();

  /** The container logger's lines. */
  private final LogLineCapture lines = new LogLineCapture(CONTAINER_LOGGER);

  /** The handler under test. */
  private final ObservedJmsErrorHandler handler =
      new ObservedJmsErrorHandler(observationRegistry, LoggerFactory.getLogger(CONTAINER_LOGGER));

  /** Lets the registry keep observations and starts capturing the container's lines. */
  @BeforeEach
  void keepObservationsAndCaptureLines() {
    observationRegistry
        .observationConfig()
        .observationHandler((ObservationHandler<Observation.Context>) context -> true);
    lines.start();
  }

  /** Stops capturing the container's lines. */
  @AfterEach
  void stopCapturingLines() {
    lines.stop();
  }

  @Test
  @DisplayName("marks the current message's observation with the listener's failure")
  void marksCurrentObservation() {
    var observation = Observation.start("jms.message.process", observationRegistry);
    try (var _ = observation.openScope()) {
      handler.handleError(listenerError);
    }
    observation.stop();

    assertThat(observation.getContext().getError()).isSameAs(listenerError);
  }

  @Test
  @DisplayName("logs Spring's warning with the failure under the container's logger")
  void logsSpringWarning() {
    handler.handleError(listenerError);

    var line = lines.onlyLine();
    assertThat(line.getLevel()).isEqualTo(Level.WARN);
    assertThat(line.getFormattedMessage()).isEqualTo(ObservedJmsErrorHandler.LISTENER_FAILED);
    assertThat(line.getThrowableProxy().getMessage()).isEqualTo(listenerError.getMessage());
  }

  @Test
  @DisplayName("with no current observation, only logs the warning")
  void noObservationOnlyLogs() {
    handler.handleError(listenerError);

    assertThat(observationRegistry.getCurrentObservation()).isNull();
    assertThat(lines.lines()).hasSize(1);
  }
}
