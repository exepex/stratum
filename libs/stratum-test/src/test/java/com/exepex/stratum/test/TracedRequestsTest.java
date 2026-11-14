package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.tracing.Span;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TracedRequests runs work in a request and keeps the spans that ended")
class TracedRequestsTest {

  /** The name of the client span the work starts. */
  private static final String STATEMENT = "SELECT";

  /** The message of the work's failure. */
  private static final String FAILURE = "failed";

  private TracedRequests traced = TracedRequests.sampled();

  /** Closes the tracer. */
  @AfterEach
  void close() {
    traced.close();
  }

  /** Starts and ends a client span under the current span, as a statement's execute does. */
  private void runStatement() {
    var tracer = traced.tracer();
    tracer
        .spanBuilder()
        .setParent(tracer.currentTraceContext().context())
        .name(STATEMENT)
        .kind(Span.Kind.CLIENT)
        .start()
        .end();
  }

  /** Fails, as a statement's execute can. */
  private void fail() throws SQLException {
    throw new SQLException(FAILURE);
  }

  @Nested
  @DisplayName("in a sampled request")
  class Sampled {

    @Test
    @DisplayName("the work's client span is a child of the request's span")
    void keepsClientSpan() throws SQLException {
      traced.inRequest(TracedRequestsTest.this::runStatement);

      assertThat(traced.statementSpans()).extracting(SpanData::getName).containsExactly(STATEMENT);
      assertThat(traced.allSpans())
          .extracting(SpanData::getName)
          .containsExactly(STATEMENT, TracedRequests.REQUEST);
      assertThat(traced.statementSpans().getFirst().getParentSpanId())
          .isEqualTo(traced.allSpans().getLast().getSpanId());
    }

    @Test
    @DisplayName("the request's span ends even when the work throws")
    void endsOnFailure() {
      assertThatThrownBy(() -> traced.inRequest(TracedRequestsTest.this::fail)).hasMessage(FAILURE);

      assertThat(traced.allSpans())
          .extracting(SpanData::getName)
          .containsExactly(TracedRequests.REQUEST);
    }

    @Test
    @DisplayName("reset forgets the spans that ended so far")
    void resetForgets() throws SQLException {
      traced.inRequest(TracedRequestsTest.this::runStatement);

      traced.reset();

      assertThat(traced.allSpans()).isEmpty();
    }

    @Test
    @DisplayName("the tracer provider gives the same tracer, as a bean factory does")
    void providerGivesTracer() {
      assertThat(traced.tracers().getObject()).isSameAs(traced.tracer());
    }
  }

  @Test
  @DisplayName("in a request that is not sampled, no span is kept")
  void unsampled() throws SQLException {
    traced.close();
    traced = TracedRequests.unsampled();

    traced.inRequest(this::runStatement);

    assertThat(traced.allSpans()).isEmpty();
  }
}
