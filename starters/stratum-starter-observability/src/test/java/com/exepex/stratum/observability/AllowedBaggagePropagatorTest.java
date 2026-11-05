package com.exepex.stratum.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.logging.LoggingContextConstants;
import com.exepex.stratum.observability.testsupport.BaggageTestData;
import com.exepex.stratum.observability.testsupport.MessageTraceChecks;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapPropagator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("AllowedBaggagePropagator keeps only the allowed baggage entries")
class AllowedBaggagePropagatorTest {

  /** The propagator under test, around OpenTelemetry's W3C baggage propagator. */
  private final TextMapPropagator propagator =
      new AllowedBaggagePropagator(
          W3CBaggagePropagator.getInstance(),
          Set.of(LoggingContextConstants.CORRELATION_ID, BaggageTestData.REMOTE_FIELD));

  @Nested
  @DisplayName("when it reads a call or message")
  class Reading {

    @Test
    @DisplayName("it keeps the allowed names and drops the others, a casing variant included")
    void keepsOnlyAllowedNames() {
      assertThat(BaggageTestData.namesRead(propagator, BaggageTestData.CALLER_BAGGAGE))
          .containsExactly(LoggingContextConstants.CORRELATION_ID);
    }

    @Test
    @DisplayName("it keeps every allowed name, a remote field included")
    void keepsEveryAllowedName() {
      assertThat(
              BaggageTestData.namesRead(
                  propagator, BaggageTestData.CORRELATION_ID_ENTRY + ",tenantId=t-1"))
          .containsExactlyInAnyOrder(
              LoggingContextConstants.CORRELATION_ID, BaggageTestData.REMOTE_FIELD);
    }

    @Test
    @DisplayName("with no baggage header, the context has no baggage")
    void readsNoBaggageWithoutHeader() {
      var context = propagator.extract(Context.root(), Map.of(), BaggageTestData.HEADERS);

      assertThat(Baggage.fromContext(context).isEmpty()).isTrue();
    }
  }

  @Nested
  @DisplayName("when it sends a call or message")
  class Sending {

    @Test
    @DisplayName("it sends the allowed names and none of the others")
    void sendsOnlyAllowedNames() {
      var baggage =
          Baggage.builder()
              .put(LoggingContextConstants.CORRELATION_ID, MessageTraceChecks.ORDER_CORRELATION_ID)
              .put("evil", "SECRET")
              .put("CORRELATIONID", "leak")
              .build();

      assertThat(sentBaggageHeader(Context.root().with(baggage)))
          .isEqualTo(BaggageTestData.CORRELATION_ID_ENTRY);
    }

    @Test
    @DisplayName("with no baggage, it sends no baggage header")
    void sendsNoHeaderWithoutBaggage() {
      assertThat(sentBaggageHeader(Context.root())).isNull();
    }

    /** Returns the baggage header the propagator writes for this context, or null for none. */
    private String sentBaggageHeader(Context context) {
      var headers = new HashMap<String, String>();
      propagator.inject(context, headers, Map::put);
      return headers.get(BaggageTestData.BAGGAGE_HEADER);
    }
  }

  @Test
  @DisplayName("it names the same headers as the propagator it wraps")
  void namesWrappedFields() {
    assertThat(propagator.fields()).isEqualTo(W3CBaggagePropagator.getInstance().fields());
  }
}
