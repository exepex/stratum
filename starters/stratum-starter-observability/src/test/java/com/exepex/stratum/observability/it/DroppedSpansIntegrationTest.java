package com.exepex.stratum.observability.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.observability.ObservabilityConstants;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
    classes = ReactiveOrdersApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "management.tracing.sampling.probability=1.0",
      // Stratum's startup check needs a span address; nothing is sent to it during the test.
      "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:1/v1/traces",
      "management.otlp.metrics.export.enabled=false",
      "orders.downstream-url=http://localhost:1",
      // A queue of one span, sent only after an hour: every span after the first is dropped.
      "management.opentelemetry.tracing.export.max-queue-size=1",
      "management.opentelemetry.tracing.export.schedule-delay=1h"
    })
// Closes the application after this class, so its tracing never reaches the next one.
@DirtiesContext
@DisplayName("Spans dropped because the export queue is full are counted")
class DroppedSpansIntegrationTest {

  /** How many spans the test ends at once. */
  private static final int SPANS_ENDED = 5;

  /**
   * The fewest drops expected: the queue holds one span, and the export worker may take one more
   * before the test ends the rest.
   */
  private static final double FEWEST_DROPPED = SPANS_ENDED - 2;

  @Autowired private SdkTracerProvider tracerProvider;

  @Autowired private MeterRegistry meterRegistry;

  @Test
  @DisplayName("the dropped-span counter holds the spans the full queue refused")
  void countsDroppedSpans() {
    IntStream.range(0, SPANS_ENDED)
        .forEach(_ -> tracerProvider.get("test").spanBuilder("order.step").startSpan().end());

    assertThat(
            meterRegistry
                .get(ObservabilityConstants.DROPPED_SPANS_METRIC)
                .functionCounter()
                .count())
        .isGreaterThanOrEqualTo(FEWEST_DROPPED);
  }
}
