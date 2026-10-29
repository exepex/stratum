package com.exepex.stratum.observability;

import com.exepex.stratum.core.error.LoggingContextConstants;
import io.micrometer.tracing.otel.bridge.EventListener;
import io.micrometer.tracing.otel.bridge.EventPublishingContextWrapper.ScopeAttachedEvent;
import io.micrometer.tracing.otel.bridge.EventPublishingContextWrapper.ScopeClosedEvent;
import io.micrometer.tracing.otel.bridge.EventPublishingContextWrapper.ScopeRestoredEvent;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * Puts the correlation ID in the logging context (SLF4J's MDC, key {@code correlationId}) of every
 * traced operation, so every log line and every error report carries it. The ID is the {@code
 * correlationId} baggage entry, when one is set and is 1 to 128 safe characters; otherwise it is
 * the trace ID, which every service in the same trace shares. A baggage ID is also recorded on each
 * new span, so a trace can be found by it.
 */
public class CorrelationIdRecorder implements EventListener, SpanProcessor {

  /**
   * Updates the logging context when the current span changes, as Spring Boot does for the trace
   * and span IDs.
   *
   * @param event what changed in the tracing context
   */
  @Override
  public void onEvent(Object event) {
    switch (event) {
      case ScopeAttachedEvent attachedScope ->
          putCorrelationId(attachedScope.getSpan(), attachedScope.getBaggage());
      case ScopeRestoredEvent restoredScope ->
          putCorrelationId(restoredScope.getSpan(), restoredScope.getBaggage());
      case ScopeClosedEvent _ -> MDC.remove(LoggingContextConstants.CORRELATION_ID);
      default -> {
        // Other events do not change the current span.
      }
    }
  }

  /**
   * Records the baggage correlation ID on a span that starts, if there is a usable one.
   *
   * @param parentContext the context the span starts in
   * @param span the span that starts
   */
  @Override
  public void onStart(Context parentContext, ReadWriteSpan span) {
    correlationIdFromBaggage(Baggage.fromContext(parentContext))
        .ifPresent(
            correlationId ->
                span.setAttribute(LoggingContextConstants.CORRELATION_ID, correlationId));
  }

  /**
   * Returns {@code true}: the ID is recorded when a span starts.
   *
   * @return {@code true}
   */
  @Override
  public boolean isStartRequired() {
    return true;
  }

  /**
   * Does nothing: the ID is recorded when a span starts.
   *
   * @param span the span that ended
   */
  @Override
  public void onEnd(ReadableSpan span) {
    // Nothing to do when a span ends.
  }

  /**
   * Returns {@code false}: nothing is done when a span ends.
   *
   * @return {@code false}
   */
  @Override
  public boolean isEndRequired() {
    return false;
  }

  /** Puts the correlation ID of the span that became current; leaves the context without one. */
  private void putCorrelationId(@Nullable Span span, Baggage baggage) {
    if (span != null) {
      MDC.put(
          LoggingContextConstants.CORRELATION_ID,
          correlationIdFromBaggage(baggage).orElse(span.getSpanContext().getTraceId()));
    }
  }

  /** Returns the baggage correlation ID, if one is set and is usable. */
  private Optional<String> correlationIdFromBaggage(Baggage baggage) {
    return Optional.ofNullable(baggage.getEntryValue(LoggingContextConstants.CORRELATION_ID))
        .filter(
            correlationId -> LoggingContextConstants.USABLE_ID.matcher(correlationId).matches());
  }
}
