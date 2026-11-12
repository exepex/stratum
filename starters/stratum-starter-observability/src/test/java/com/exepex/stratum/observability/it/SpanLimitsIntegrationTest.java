package com.exepex.stratum.observability.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.observability.testsupport.ExportedSpans;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanLimits;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(
    classes = {ReactiveOrdersApplication.class, SpanLimitsIntegrationTest.SavedSpans.class},
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "management.tracing.sampling.probability=1.0",
      // Stratum's startup check needs a span address; sending to it fails without harm, and the
      // test reads the spans from its own exporter.
      "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:1/v1/traces",
      "management.otlp.metrics.export.enabled=false",
      "orders.downstream-url=http://localhost:1"
    })
// Closes the application after this class, so its tracing never reaches the next one.
@DirtiesContext
@DisplayName("Each span has a size bound before it is sent")
class SpanLimitsIntegrationTest {

  /** The name of the span the test fills past every limit. */
  private static final String OVERSIZED_SPAN = "order.oversized";

  /** The attribute that holds a value far longer than the limit. */
  private static final AttributeKey<String> LONG_VALUE = AttributeKey.stringKey("order.note");

  /** A value of 2 MB, as a header or body a service copies into a span could be. */
  private static final String TWO_MEGABYTES = "x".repeat(2 * 1024 * 1024);

  /** How many attributes the test sets on the span, the long one included. */
  private static final int ATTRIBUTES_SET = 70;

  /** How many events the test adds to the span. */
  private static final int EVENTS_ADDED = 20;

  /** How long the test waits for the span to be sent. */
  private static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(10);

  /** Stratum's default longest attribute value, in characters. */
  private static final int MAX_VALUE_LENGTH = 1024;

  /** Stratum's default number of attributes kept on one span. */
  private static final int MAX_ATTRIBUTES = 64;

  /** Stratum's default number of events kept on one span. */
  private static final int MAX_EVENTS = 16;

  /** A higher attribute limit a service sets for itself. */
  private static final int SERVICE_MAX_ATTRIBUTES = 100;

  /** Keeps the spans Spring Boot sends, beside its OTLP exporter. */
  @TestConfiguration(proxyBeanMethods = false)
  static class SavedSpans {

    /**
     * Gives Spring Boot an exporter that keeps every span it sends.
     *
     * @return the exporter
     */
    @Bean
    ExportedSpans exportedSpans() {
      return new ExportedSpans();
    }
  }

  /** Makes spans with the limits Spring Boot built from its keys. */
  @Autowired private SdkTracerProvider tracerProvider;

  /** The limits Spring Boot built from its keys. */
  @Autowired private SpanLimits spanLimits;

  /** The spans Spring Boot sent. */
  @Autowired private ExportedSpans exportedSpans;

  @Test
  @DisplayName("Spring Boot builds its span limits from Stratum's defaults")
  void usesStratumLimits() {
    assertThat(spanLimits.getMaxAttributeValueLength()).isEqualTo(MAX_VALUE_LENGTH);
    assertThat(spanLimits.getMaxNumberOfAttributes()).isEqualTo(MAX_ATTRIBUTES);
    assertThat(spanLimits.getMaxNumberOfEvents()).isEqualTo(MAX_EVENTS);
  }

  @Test
  @DisplayName(
      "a 2 MB value is cut to 1,024 characters, and attributes past 64 and events past 16 are"
          + " dropped")
  void boundsOversizedSpan() {
    sendOversizedSpan();

    // Micrometer's exporter rebuilds each span with its kept attributes and events only
    // (OtelFinishedSpan.MutableSpanData), so the dropped counts do not reach any exporter.
    var span = exportedSpans.named(OVERSIZED_SPAN);
    assertThat(span.getAttributes().get(LONG_VALUE)).hasSize(MAX_VALUE_LENGTH);
    assertThat(span.getAttributes().size()).isEqualTo(MAX_ATTRIBUTES);
    assertThat(span.getEvents()).hasSize(MAX_EVENTS);
  }

  /** Ends one span filled past every limit and sends it at once. */
  private void sendOversizedSpan() {
    Span span = tracerProvider.get("test").spanBuilder(OVERSIZED_SPAN).startSpan();
    // The long value goes first, so it is among the 64 attributes kept.
    span.setAttribute(LONG_VALUE, TWO_MEGABYTES);
    IntStream.range(1, ATTRIBUTES_SET)
        .forEach(index -> span.setAttribute("order.item." + index, index));
    IntStream.range(0, EVENTS_ADDED).forEach(index -> span.addEvent("order.step"));
    span.end();
    tracerProvider.forceFlush().join(EXPORT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
  }

  @Nested
  @TestPropertySource(
      properties =
          "management.opentelemetry.tracing.limits.max-attributes=" + SERVICE_MAX_ATTRIBUTES)
  @DisplayName("with the service's own limit")
  class WithServiceLimit {

    @Test
    @DisplayName("Spring Boot uses the service's value")
    void usesServiceLimit() {
      assertThat(spanLimits.getMaxNumberOfAttributes()).isEqualTo(SERVICE_MAX_ATTRIBUTES);
    }
  }
}
