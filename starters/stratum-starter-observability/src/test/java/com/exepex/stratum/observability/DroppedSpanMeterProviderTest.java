package com.exepex.stratum.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DroppedSpanMeterProvider counts the spans the batch span processor drops")
class DroppedSpanMeterProviderTest {

  /** Spans the processor drops in a test. */
  private static final long DROPPED_SPANS = 3;

  /** Spans the processor sends in a test, which are not counted. */
  private static final long SENT_SPANS = 5;

  /** The attributes the SDK records with each dropped span. */
  private static final Attributes DROPPED =
      Attributes.builder().put(ObservabilityConstants.DROPPED_ATTRIBUTE, true).build();

  /** The attributes the SDK records with each sent span. */
  private static final Attributes SENT =
      Attributes.builder().put(ObservabilityConstants.DROPPED_ATTRIBUTE, false).build();

  private final DroppedSpanMeterProvider provider = new DroppedSpanMeterProvider();

  /** Returns the processed-spans counter of the given meter, as the SDK builds it. */
  private LongCounter processedSpans(String meterName) {
    return provider
        .get(meterName)
        .counterBuilder(ObservabilityConstants.PROCESSED_SPANS_INSTRUMENT)
        .build();
  }

  @Test
  @DisplayName("counts only the spans recorded as dropped")
  void countsDroppedSpans() {
    var counter = processedSpans(ObservabilityConstants.SPAN_PROCESSOR_METER);
    counter.add(DROPPED_SPANS, DROPPED);
    counter.add(SENT_SPANS, SENT);

    assertThat(provider.droppedSpans()).isEqualTo(DROPPED_SPANS);
  }

  @Test
  @DisplayName("before any span, the count is zero")
  void startsAtZero() {
    assertThat(provider.droppedSpans()).isZero();
  }

  @Test
  @DisplayName("another library's meter records nothing")
  void ignoresOtherMeters() {
    processedSpans("io.opentelemetry.exporters.otlp").add(DROPPED_SPANS, DROPPED);

    assertThat(provider.droppedSpans()).isZero();
  }

  @Test
  @DisplayName("the Micrometer counter reads the same count")
  void bindsCounter() {
    var registry = new SimpleMeterRegistry();
    provider.bindTo(registry);
    processedSpans(ObservabilityConstants.SPAN_PROCESSOR_METER).add(DROPPED_SPANS, DROPPED);

    assertThat(registry.get(ObservabilityConstants.DROPPED_SPANS_METRIC).functionCounter().count())
        .isEqualTo(DROPPED_SPANS);
  }
}
