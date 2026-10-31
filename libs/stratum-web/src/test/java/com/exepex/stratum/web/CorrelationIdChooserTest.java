package com.exepex.stratum.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.web.CorrelationIdChooser.Choice;
import com.exepex.stratum.web.testsupport.WebTestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CorrelationIdChooser picks the header, then the baggage, then the trace ID")
class CorrelationIdChooserTest {

  /** A correlation ID the caller sent in its baggage. */
  private static final String BAGGAGE_ID = "order-flow-9";

  @Nested
  @DisplayName("when the caller's values are accepted")
  class CallerAccepted {

    /** The chooser with the default settings. */
    private final CorrelationIdChooser chooser =
        new CorrelationIdChooser(WebTestData.defaults().build());

    @Test
    @DisplayName("the header wins over the baggage, and is put in the baggage")
    void headerWins() {
      assertThat(chooser.choose(WebTestData.CORRELATION_ID, BAGGAGE_ID, WebTestData.TRACE_ID))
          .hasValue(new Choice(WebTestData.CORRELATION_ID, true));
    }

    @Test
    @DisplayName("without a header, the baggage is used and left as it is")
    void baggageWithoutHeader() {
      assertThat(chooser.choose(null, BAGGAGE_ID, WebTestData.TRACE_ID))
          .hasValue(new Choice(BAGGAGE_ID, false));
    }

    @Test
    @DisplayName("an unsafe header is skipped for the baggage")
    void unsafeHeaderSkipped() {
      assertThat(chooser.choose("a\nb", BAGGAGE_ID, WebTestData.TRACE_ID))
          .hasValue(new Choice(BAGGAGE_ID, false));
    }

    @Test
    @DisplayName("without caller values, the trace ID is used and no baggage is added")
    void traceIdAlone() {
      assertThat(chooser.choose(null, null, WebTestData.TRACE_ID))
          .hasValue(new Choice(WebTestData.TRACE_ID, false));
    }

    @Test
    @DisplayName("without tracing, a header is still used")
    void headerWithoutTracing() {
      assertThat(chooser.choose(WebTestData.CORRELATION_ID, null, null))
          .hasValue(new Choice(WebTestData.CORRELATION_ID, true));
    }

    @Test
    @DisplayName("with nothing at all, there is no correlation ID")
    void nothing() {
      assertThat(chooser.choose(null, null, null)).isEmpty();
    }
  }

  @Nested
  @DisplayName("when accept-from-caller is false")
  class CallerIgnored {

    /** The chooser that ignores the caller's values. */
    private final CorrelationIdChooser chooser =
        new CorrelationIdChooser(
            WebTestData.defaults()
                .correlation(
                    WebProperties.CorrelationSettings.builder().acceptFromCaller(false).build())
                .build());

    @Test
    @DisplayName("the header is ignored for the trace ID")
    void headerIgnored() {
      assertThat(chooser.choose(WebTestData.CORRELATION_ID, null, WebTestData.TRACE_ID))
          .hasValue(new Choice(WebTestData.TRACE_ID, false));
    }

    @Test
    @DisplayName("the caller's baggage is replaced with the trace ID")
    void baggageReplaced() {
      assertThat(chooser.choose(null, BAGGAGE_ID, WebTestData.TRACE_ID))
          .hasValue(new Choice(WebTestData.TRACE_ID, true));
    }
  }
}
