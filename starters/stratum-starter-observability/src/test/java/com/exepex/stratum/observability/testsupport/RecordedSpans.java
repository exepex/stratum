package com.exepex.stratum.observability.testsupport;

import static org.awaitility.Awaitility.await;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Keeps every span that ends, so a test can read its parent and links. Spring Boot passes every
 * {@code SpanProcessor} bean to OpenTelemetry.
 */
public class RecordedSpans implements SpanProcessor {

  /** How long a test waits for a span to end. */
  private static final Duration SPAN_END_TIMEOUT = Duration.ofSeconds(5);

  /** The spans that ended, oldest first. */
  private final List<SpanData> endedSpans = new CopyOnWriteArrayList<>();

  /**
   * Returns the ended span with this name, waiting a little for it: a {@code Flux} ends its span
   * after the subscriber has seen it complete.
   *
   * @param name the span's name
   * @return the span
   * @throws org.awaitility.core.ConditionTimeoutException if no span with the name ends in time
   */
  public SpanData named(String name) {
    return await()
        .atMost(SPAN_END_TIMEOUT)
        .until(
            () -> endedSpans.stream().filter(span -> span.getName().equals(name)).findFirst(),
            Optional::isPresent)
        .orElseThrow();
  }

  /**
   * Does nothing: spans are kept when they end.
   *
   * @param parentContext the context the span starts in
   * @param span the span that starts
   */
  @Override
  public void onStart(Context parentContext, ReadWriteSpan span) {
    // Spans are kept when they end.
  }

  /**
   * Returns {@code false}: nothing is done when a span starts.
   *
   * @return {@code false}
   */
  @Override
  public boolean isStartRequired() {
    return false;
  }

  /**
   * Keeps the span.
   *
   * @param span the span that ended
   */
  @Override
  public void onEnd(ReadableSpan span) {
    endedSpans.add(span.toSpanData());
  }

  /**
   * Returns {@code true}: spans are kept when they end.
   *
   * @return {@code true}
   */
  @Override
  public boolean isEndRequired() {
    return true;
  }
}
