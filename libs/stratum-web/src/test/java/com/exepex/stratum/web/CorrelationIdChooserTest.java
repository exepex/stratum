package com.exepex.stratum.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.web.testsupport.WebTestData;
import io.micrometer.tracing.Baggage;
import io.micrometer.tracing.Tracer;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CorrelationIdChooser puts the caller's ID in the baggage and reads it back")
class CorrelationIdChooserTest {

  /** A correlation ID the caller sent in its baggage. */
  private static final String BAGGAGE_ID = "order-flow-9";

  /** The baggage entry that carries {@link #BAGGAGE_ID}. */
  private static final String BAGGAGE_ENTRY =
      LoggingContextConstants.CORRELATION_ID + "=" + BAGGAGE_ID;

  /** The baggage entry that carries the caller's header ID. */
  private static final String HEADER_ENTRY =
      LoggingContextConstants.CORRELATION_ID + "=" + WebTestData.CORRELATION_ID;

  /** A baggage entry of the caller's own, which is kept. */
  private static final String TENANT_ENTRY = "tenant=acme";

  /** The chooser with the default settings. */
  private final CorrelationIdChooser chooser =
      new CorrelationIdChooser(WebTestData.defaults().build());

  @Nested
  @DisplayName("rewrittenBaggage, when the caller's values are accepted")
  class CallerAccepted {

    @Test
    @DisplayName("the header replaces the baggage ID, and the other entries are kept")
    void headerReplacesBaggageId() {
      assertThat(
              chooser.rewrittenBaggage(
                  WebTestData.CORRELATION_ID, List.of(TENANT_ENTRY + ", " + BAGGAGE_ENTRY)))
          .hasValue(List.of(TENANT_ENTRY, HEADER_ENTRY));
    }

    @Test
    @DisplayName("a usable baggage ID without a header leaves the baggage as it is")
    void baggageIdKept() {
      assertThat(chooser.rewrittenBaggage(null, List.of(BAGGAGE_ENTRY))).isEmpty();
    }

    @Test
    @DisplayName("an unsafe header is skipped for the baggage ID")
    void unsafeHeaderSkipped() {
      assertThat(chooser.rewrittenBaggage("a\nb", List.of(BAGGAGE_ENTRY))).isEmpty();
    }

    @Test
    @DisplayName(
        "a percent-encoded baggage ID that is safe once decoded is left as it is, for tracing"
            + " decodes it")
    void encodedBaggageIdKept() {
      assertThat(
              chooser.rewrittenBaggage(
                  null, List.of(LoggingContextConstants.CORRELATION_ID + "=checkout%3A777")))
          .isEmpty();
    }

    @Test
    @DisplayName("an unsafe baggage ID, or one with a broken escape, is removed")
    void unsafeBaggageIdRemoved() {
      assertThat(
              chooser.rewrittenBaggage(
                  null,
                  List.of(TENANT_ENTRY, LoggingContextConstants.CORRELATION_ID + "=checkout%3")))
          .hasValue(List.of(TENANT_ENTRY));
    }

    @Test
    @DisplayName(
        "a second correlationId entry, which tracing would keep unchecked, is replaced by the"
            + " first usable one")
    void duplicateBaggageIdsReplaced() {
      assertThat(
              chooser.rewrittenBaggage(
                  null,
                  List.of(
                      BAGGAGE_ENTRY + "," + LoggingContextConstants.CORRELATION_ID + "=%0Aforged")))
          .hasValue(List.of(BAGGAGE_ENTRY));
    }

    @Test
    @DisplayName("without caller values, the baggage is left as it is")
    void nothingToChange() {
      assertThat(chooser.rewrittenBaggage(null, List.of(TENANT_ENTRY))).isEmpty();
    }
  }

  @Nested
  @DisplayName("rewrittenBaggage, when accept-from-caller is false")
  class CallerIgnored {

    /** The chooser that ignores the caller's values. */
    private final CorrelationIdChooser ignoringChooser =
        new CorrelationIdChooser(
            WebTestData.defaults()
                .correlation(
                    WebProperties.CorrelationSettings.builder().acceptFromCaller(false).build())
                .build());

    @Test
    @DisplayName("the header is not put in the baggage")
    void headerIgnored() {
      assertThat(ignoringChooser.rewrittenBaggage(WebTestData.CORRELATION_ID, List.of())).isEmpty();
    }

    @Test
    @DisplayName("the caller's baggage ID is removed, so the trace ID is used")
    void baggageIdRemoved() {
      assertThat(ignoringChooser.rewrittenBaggage(null, List.of(BAGGAGE_ENTRY)))
          .hasValue(List.of());
    }
  }

  @Nested
  @DisplayName("currentCorrelationId")
  class CurrentCorrelationId {

    /** The request's tracer. */
    private final Tracer tracer = mock(Tracer.class, RETURNS_DEEP_STUBS);

    @Test
    @DisplayName("is the baggage ID when the request has one")
    void baggageId() {
      var baggage = mock(Baggage.class);
      when(baggage.get()).thenReturn(BAGGAGE_ID);
      when(tracer.getBaggage(LoggingContextConstants.CORRELATION_ID)).thenReturn(baggage);

      assertThat(chooser.currentCorrelationId(tracer)).hasValue(BAGGAGE_ID);
    }

    @Test
    @DisplayName("is the trace ID when the request has no baggage ID")
    void traceId() {
      when(tracer.getBaggage(LoggingContextConstants.CORRELATION_ID)).thenReturn(null);
      when(tracer.currentSpan().context().traceId()).thenReturn(WebTestData.TRACE_ID);

      assertThat(chooser.currentCorrelationId(tracer)).hasValue(WebTestData.TRACE_ID);
    }

    @Test
    @DisplayName("is nothing when the request is not traced")
    void untraced() {
      assertThat(chooser.currentCorrelationId(Tracer.NOOP)).isEmpty();
    }
  }
}
