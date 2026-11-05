package com.exepex.stratum.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import lombok.Getter;

/**
 * A finished span without exception data: no {@code exception} event, no {@code exception} or
 * {@code error.type} attribute, and an {@code ERROR} status without its description, which is the
 * exception's message. Everything else is the span's own.
 */
final class ExceptionFreeSpanData extends DelegatingSpanData {

  /** The span's attributes, without {@code exception} and {@code error.type}. */
  @Getter private final Attributes attributes;

  /** The span's events, without the exception events. */
  private final List<EventData> events;

  /** Builds the view once, so each exporter reads the same filtered values. */
  private ExceptionFreeSpanData(SpanData span) {
    super(span);
    attributes =
        span.getAttributes().toBuilder()
            .removeIf(ExceptionFreeSpanData::isExceptionAttribute)
            .build();
    events =
        span.getEvents().stream()
            .filter(Predicate.not(ExceptionFreeSpanData::isExceptionEvent))
            .toList();
  }

  /**
   * Returns the span without its exception data, or the span itself if it holds none, so a span
   * with nothing to remove is not copied.
   *
   * @param span the finished span
   * @return the span as it may be sent
   */
  static SpanData from(SpanData span) {
    return holdsExceptionData(span) ? new ExceptionFreeSpanData(span) : span;
  }

  /** Returns {@code true} if the span holds exception data that this view removes. */
  private static boolean holdsExceptionData(SpanData span) {
    return !span.getStatus().getDescription().isEmpty()
        || span.getAttributes().asMap().keySet().stream()
            .anyMatch(ExceptionFreeSpanData::isExceptionAttribute)
        || span.getEvents().stream().anyMatch(ExceptionFreeSpanData::isExceptionEvent);
  }

  /** Returns {@code true} for an attribute that holds the exception's type. */
  private static boolean isExceptionAttribute(AttributeKey<?> key) {
    return ObservabilityConstants.EXCEPTION_ATTRIBUTES.contains(key.getKey());
  }

  /**
   * Returns the span's attribute count, less the attributes this view removed, so the exported
   * count of dropped attributes still means those cut by the span limits.
   *
   * @return the attribute count
   */
  @Override
  public int getTotalAttributeCount() {
    return super.getTotalAttributeCount() - (super.getAttributes().size() - attributes.size());
  }

  /**
   * Returns the span's events without its {@code exception} events.
   *
   * @return the events
   */
  @Override
  public List<EventData> getEvents() {
    return Collections.unmodifiableList(events);
  }

  /**
   * Returns the span's event count, less the events this view removed, so the exported count of
   * dropped events still means those cut by the span limits.
   *
   * @return the event count
   */
  @Override
  public int getTotalRecordedEvents() {
    return super.getTotalRecordedEvents() - (super.getEvents().size() - events.size());
  }

  /**
   * Returns the span's status code without a description: {@code ERROR} stays {@code ERROR}.
   *
   * @return the status
   */
  @Override
  public StatusData getStatus() {
    var statusCode = super.getStatus().getStatusCode();
    return statusCode == StatusCode.ERROR ? StatusData.error() : super.getStatus();
  }

  /** Returns {@code true} for an event that holds an exception. */
  private static boolean isExceptionEvent(EventData event) {
    return ObservabilityConstants.EXCEPTION_EVENT.equals(event.getName());
  }
}
