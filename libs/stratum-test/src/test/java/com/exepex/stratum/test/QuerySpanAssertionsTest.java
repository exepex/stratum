package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QuerySpanAssertions checks a statement span carries only its operation and database")
class QuerySpanAssertionsTest {

  /** A value a statement held as a literal. */
  private static final String LITERAL = "ada@example.com";

  /** The operation of the statement under test. */
  private static final String SELECT = "SELECT";

  /** The spans that ended. */
  private final List<SpanData> ended = new CopyOnWriteArrayList<>();

  private final SdkTracerProvider tracerProvider =
      SdkTracerProvider.builder().addSpanProcessor(new Recorder(ended)).build();

  /** Closes the tracer provider. */
  @AfterEach
  void close() {
    tracerProvider.close();
  }

  /** Ends a span of the given kind, name and attributes, with an event, and returns it. */
  private SpanData span(SpanKind kind, String name, Attributes attributes, String eventText) {
    var span =
        tracerProvider
            .get("test")
            .spanBuilder(name)
            .setSpanKind(kind)
            .setAllAttributes(attributes)
            .startSpan();
    span.addEvent(eventText);
    span.end();
    return ended.getLast();
  }

  /** Returns a clean statement span's attributes. */
  private Attributes clean() {
    return Attributes.of(
        QuerySpanAssertions.SYSTEM, "postgresql",
        QuerySpanAssertions.NAMESPACE, "orders",
        QuerySpanAssertions.OPERATION, SELECT);
  }

  @Test
  @DisplayName("passes a client span with only the database and its operation")
  void passesCleanSpan() {
    var span = span(SpanKind.CLIENT, SELECT, clean(), "exception");

    assertThatCode(() -> QuerySpanAssertions.assertStatementSpan(span, SELECT, LITERAL))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("fails a span of another kind, name or operation")
  void failsWrongShape() {
    var internal = span(SpanKind.INTERNAL, SELECT, clean(), "e");
    var misnamed = span(SpanKind.CLIENT, "INSERT", clean(), "e");

    assertThatThrownBy(() -> QuerySpanAssertions.assertStatementSpan(internal, SELECT, LITERAL))
        .isInstanceOf(AssertionError.class);
    assertThatThrownBy(() -> QuerySpanAssertions.assertStatementSpan(misnamed, SELECT, LITERAL))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  @DisplayName("fails a span with any other attribute, such as the SQL text")
  void failsExtraAttribute() {
    var span =
        span(
            SpanKind.CLIENT,
            SELECT,
            clean().toBuilder().put("db.query.text", "SELECT 1").build(),
            "e");

    assertThatThrownBy(() -> QuerySpanAssertions.assertStatementSpan(span, SELECT, LITERAL))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  @DisplayName("fails a span whose attributes or events carry the literal")
  void failsLiteral() {
    var inAttribute =
        span(
            SpanKind.CLIENT,
            SELECT,
            clean().toBuilder().put(QuerySpanAssertions.NAMESPACE, LITERAL).build(),
            "e");
    var inEvent = span(SpanKind.CLIENT, SELECT, clean(), "duplicate " + LITERAL);

    assertThatThrownBy(() -> QuerySpanAssertions.assertStatementSpan(inAttribute, SELECT, LITERAL))
        .isInstanceOf(AssertionError.class);
    assertThatThrownBy(() -> QuerySpanAssertions.assertStatementSpan(inEvent, SELECT, LITERAL))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  @DisplayName("statementSpans keeps only the client spans")
  void keepsClientSpans() {
    var client = span(SpanKind.CLIENT, SELECT, clean(), "e");
    span(SpanKind.SERVER, "request", Attributes.empty(), "e");

    assertThat(QuerySpanAssertions.statementSpans(ended)).containsExactly(client);
  }

  /**
   * Keeps each span as it ends.
   *
   * @param ended the list the spans go into
   */
  private record Recorder(List<SpanData> ended) implements SpanProcessor {

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
      // Nothing to record until the span ends.
    }

    @Override
    public boolean isStartRequired() {
      return false;
    }

    @Override
    public void onEnd(ReadableSpan span) {
      ended.add(span.toSpanData());
    }

    @Override
    public boolean isEndRequired() {
      return true;
    }
  }
}
