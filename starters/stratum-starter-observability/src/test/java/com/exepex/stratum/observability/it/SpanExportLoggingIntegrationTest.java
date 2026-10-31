package com.exepex.stratum.observability.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.observability.testsupport.ObservabilityTestData;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(
    classes = OrdersApplication.class,
    properties = {
      "spring.main.web-application-type=none",
      "stratum.observability.enabled=true",
      "management.otlp.metrics.export.enabled=false",
      "management.tracing.sampling.probability=1.0"
    })
// Closes the application after these tests, so its tracing and threads never reach the next.
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("A failed span send, with no Collector at the span address")
class SpanExportLoggingIntegrationTest {

  /** OpenTelemetry's error when it cannot send spans. */
  private static final String SPAN_EXPORT_FAILED = "Failed to export spans";

  /** How long to wait for spans to be sent. */
  private static final long FLUSH_TIMEOUT_SECONDS = 15;

  /** Starts observations, which Spring turns into spans. */
  @Autowired private ObservationRegistry observationRegistry;

  /** Sends the spans. */
  @Autowired private SdkTracerProvider tracerProvider;

  /**
   * Clears the log levels Stratum sets for a Collector on this machine, because they outlive the
   * application: earlier test classes may have left them set.
   */
  @BeforeAll
  static void resetLogLevels() {
    ObservabilityTestData.resetExportLogLevels();
  }

  /** Clears the log levels again after each test, before the next application starts. */
  @AfterEach
  void resetLogLevelsAfterTest() {
    ObservabilityTestData.resetExportLogLevels();
  }

  /** Makes one span and sends it while the application still runs. */
  private void sendOneSpan() {
    Observation.start("place-order", observationRegistry).stop();
    tracerProvider.forceFlush().join(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  @Nested
  @TestPropertySource(
      properties = {
        "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:1",
        "management.opentelemetry.tracing.export.otlp.transport=http"
      })
  @DisplayName("on localhost over HTTP")
  class LocalhostOverHttp {

    @Test
    @DisplayName("drops spans without an error")
    void dropsSpansQuietly(CapturedOutput output) {
      sendOneSpan();

      assertThat(output).doesNotContain(SPAN_EXPORT_FAILED);
    }
  }

  @Nested
  @TestPropertySource(
      properties = {
        "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:1",
        "management.opentelemetry.tracing.export.otlp.transport=grpc"
      })
  @DisplayName("on localhost over gRPC")
  class LocalhostOverGrpc {

    @Test
    @DisplayName("drops spans without an error")
    void dropsSpansQuietly(CapturedOutput output) {
      sendOneSpan();

      assertThat(output).doesNotContain(SPAN_EXPORT_FAILED);
    }
  }

  @Nested
  @TestPropertySource(
      properties = {
        "management.opentelemetry.tracing.export.otlp.endpoint=http://otel-collector.invalid:1",
        "management.opentelemetry.tracing.export.otlp.transport=http"
      })
  @DisplayName("at another address over HTTP")
  class OtherAddressOverHttp {

    @Test
    @DisplayName("logs the failed send")
    void logsFailedSend(CapturedOutput output) {
      sendOneSpan();

      assertThat(output).contains(SPAN_EXPORT_FAILED);
    }
  }

  @Nested
  @TestPropertySource(
      properties = {
        "management.opentelemetry.tracing.export.otlp.endpoint=http://otel-collector.invalid:1",
        "management.opentelemetry.tracing.export.otlp.transport=grpc"
      })
  @DisplayName("at another address over gRPC")
  class OtherAddressOverGrpc {

    @Test
    @DisplayName("logs the failed send")
    void logsFailedSend(CapturedOutput output) {
      sendOneSpan();

      assertThat(output).contains(SPAN_EXPORT_FAILED);
    }
  }
}
