package com.exepex.stratum.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.observability.testsupport.ExportedSpans;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

@DisplayName("ExceptionFreeSpanExporter sends spans without the exception's type, message or trace")
class ExceptionFreeSpanExporterTest {

  /** An exception message with personal data, which must never be sent. */
  private static final String MESSAGE = "order of jane@example.com failed";

  /** A span attribute that has nothing to do with the exception. */
  private static final AttributeKey<String> ROUTE = AttributeKey.stringKey("http.route");

  /** The route the sample spans carry. */
  private static final String ROUTE_VALUE = "/orders";

  /** The attribute that holds the request's error code. */
  private static final AttributeKey<String> ERROR_CODE =
      AttributeKey.stringKey(ObservabilityConstants.ERROR_CODE_TAG);

  /** An error code a failed request carries. */
  private static final String ORDER_CODE = "ORD-0001";

  /** An event that has nothing to do with the exception. */
  private static final String SAVED_EVENT = "order.saved";

  /** What the exporter sends, after the wrapper. */
  private final ExportedSpans sent = new ExportedSpans();

  /** The exporter under test. */
  private final ExceptionFreeSpanExporter exporter = new ExceptionFreeSpanExporter(sent);

  /**
   * Ends one span recorded by the given code, passes it through the exporter and returns the span
   * it sent, with the span as it was before the exporter.
   */
  private ExportedPair export(Consumer<Span> recording) {
    var ended = new ExportedSpans();
    try (var tracerProvider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(ended)).build()) {
      var span = tracerProvider.get("test").spanBuilder("place-order").startSpan();
      recording.accept(span);
      span.end();
    }
    exporter.export(List.of(ended.single()));
    return new ExportedPair(ended.single(), sent.single());
  }

  /** Records what Spring and the SDK record for a failed request. */
  private void recordFailure(Span span) {
    span.setAttribute(ROUTE, ROUTE_VALUE);
    span.setAttribute("exception", "IllegalStateException");
    span.recordException(new IllegalStateException(MESSAGE));
    span.setStatus(StatusCode.ERROR, MESSAGE);
  }

  /**
   * A span before and after the exporter.
   *
   * @param ended the span as it ended
   * @param exported the span as it was sent
   */
  private record ExportedPair(SpanData ended, SpanData exported) {}

  @Nested
  @DisplayName("events")
  class Events {

    @Test
    @DisplayName("the exception event is dropped, with its type, message and stack trace")
    void dropsExceptionEvent() {
      var exported = export(span -> recordFailure(span)).exported();
      assertThat(exported.getEvents())
          .extracting(EventData::getName)
          .doesNotContain(ObservabilityConstants.EXCEPTION_EVENT);
      assertThat(exported.getTotalRecordedEvents()).isZero();
    }

    @Test
    @DisplayName("the exception event is dropped when it is the span's only exception data")
    void dropsOnlyExceptionEvent() {
      var exported =
          export(span -> span.recordException(new IllegalStateException(MESSAGE))).exported();
      assertThat(exported.getEvents()).isEmpty();
    }

    @Test
    @DisplayName("other events are kept with their attributes")
    void keepsOtherEvents() {
      var exported = export(span -> recordFailureAndSave(span)).exported();
      assertThat(exported.getEvents()).singleElement().satisfies(this::isSavedEvent);
      assertThat(exported.getTotalRecordedEvents()).isOne();
    }

    /** Checks that the event is the saved event with its attribute. */
    private void isSavedEvent(EventData event) {
      assertThat(event.getName()).isEqualTo(SAVED_EVENT);
      assertThat(event.getAttributes().get(ROUTE)).isEqualTo(ROUTE_VALUE);
    }
  }

  /** Records a failure and an event of the service's own. */
  private void recordFailureAndSave(Span span) {
    span.addEvent(SAVED_EVENT, Attributes.of(ROUTE, ROUTE_VALUE));
    recordFailure(span);
  }

  @Nested
  @DisplayName("attributes")
  class SpanAttributes {

    /** Values of Spring's exception attributes: a type, a full class name and Spring's none. */
    private static final List<String> EXCEPTION_ATTRIBUTE_VALUES =
        List.of("IllegalStateException", "java.lang.IllegalStateException", "none");

    @ParameterizedTest
    @FieldSource("EXCEPTION_ATTRIBUTE_VALUES")
    @DisplayName("exception and error.type are dropped whatever their value; other attributes stay")
    void dropsExceptionAttributes(String value) {
      var exported = export(span -> recordExceptionAttributes(span, value)).exported();
      assertThat(exported.getAttributes().asMap())
          .containsOnlyKeys(ROUTE)
          .containsEntry(ROUTE, ROUTE_VALUE);
      assertThat(exported.getTotalAttributeCount()).isOne();
    }

    @Test
    @DisplayName("error.code=none is dropped, even when it is the span's only attribute to drop")
    void dropsNoErrorCode() {
      var exported = export(span -> recordErrorCode(span, ObservabilityConstants.NO_ERROR_CODE));

      assertThat(exported.exported()).isNotSameAs(exported.ended());
      assertThat(exported.exported().getAttributes().asMap())
          .containsOnlyKeys(ROUTE)
          .containsEntry(ROUTE, ROUTE_VALUE);
      assertThat(exported.exported().getTotalAttributeCount()).isOne();
    }

    @Test
    @DisplayName("an error code is kept")
    void keepsErrorCode() {
      var exported = export(span -> recordErrorCode(span, ORDER_CODE)).exported();

      assertThat(exported.getAttributes().get(ERROR_CODE)).isEqualTo(ORDER_CODE);
    }
  }

  /** Records a route and an error code. */
  private void recordErrorCode(Span span, String code) {
    span.setAttribute(ROUTE, ROUTE_VALUE);
    span.setAttribute(ERROR_CODE, code);
  }

  /** Records a route and both of Spring's exception attributes with the given value. */
  private void recordExceptionAttributes(Span span, String value) {
    span.setAttribute(ROUTE, ROUTE_VALUE);
    span.setAttribute("exception", value);
    span.setAttribute("error.type", value);
  }

  @Nested
  @DisplayName("status")
  class Status {

    @Test
    @DisplayName("a failed span stays ERROR, without the exception message")
    void keepsErrorWithoutMessage() {
      assertThat(export(span -> recordFailure(span)).exported().getStatus())
          .isEqualTo(StatusData.error());
    }

    @Test
    @DisplayName("the message is dropped when it is the span's only exception data")
    void dropsOnlyMessage() {
      assertThat(export(span -> span.setStatus(StatusCode.ERROR, MESSAGE)).exported().getStatus())
          .isEqualTo(StatusData.error());
    }

    @Test
    @DisplayName("a successful span keeps its status")
    void keepsOk() {
      assertThat(export(span -> span.setStatus(StatusCode.OK)).exported().getStatus())
          .isEqualTo(StatusData.ok());
    }
  }

  @Nested
  @DisplayName("spans without exception data")
  class Untouched {

    @Test
    @DisplayName("are sent as they are, not copied")
    void sendsSameSpan() {
      var pair = export(span -> span.setAttribute(ROUTE, ROUTE_VALUE));
      assertThat(pair.exported()).isSameAs(pair.ended());
    }
  }

  @Nested
  @DisplayName("the wrapped exporter")
  class Delegation {

    @Test
    @DisplayName("is flushed and stopped with the wrapper")
    void flushesAndStopsDelegate() {
      exporter.flush();
      exporter.shutdown();
      assertThat(sent.wasFlushed()).isTrue();
      assertThat(sent.wasShutDown()).isTrue();
    }
  }
}
