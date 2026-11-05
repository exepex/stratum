package com.exepex.stratum.observability.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stands in for a span exporter: keeps every span it is given and whether it was flushed or
 * stopped.
 */
public class ExportedSpans implements SpanExporter {

  /** The spans exported so far, oldest first. */
  private final List<SpanData> spans = new CopyOnWriteArrayList<>();

  /** Whether {@link #flush()} was called. */
  private final AtomicBoolean flushCalled = new AtomicBoolean();

  /** Whether {@link #shutdown()} was called. */
  private final AtomicBoolean shutdownCalled = new AtomicBoolean();

  /**
   * Returns the one span exported so far.
   *
   * @return the span
   * @throws AssertionError if not exactly one span was exported
   */
  public SpanData single() {
    assertThat(spans).hasSize(1);
    return spans.getFirst();
  }

  /**
   * Returns the one span exported so far with the given name.
   *
   * @param name the span's name
   * @return the span
   * @throws AssertionError if not exactly one span has that name
   */
  public SpanData named(String name) {
    return assertThat(spans)
        .filteredOn(span -> name.equals(span.getName()))
        .singleElement()
        .actual();
  }

  /**
   * Returns whether the exporter was flushed.
   *
   * @return {@code true} once {@link #flush()} was called
   */
  public boolean wasFlushed() {
    return flushCalled.get();
  }

  /**
   * Returns whether the exporter was stopped.
   *
   * @return {@code true} once {@link #shutdown()} was called
   */
  public boolean wasShutDown() {
    return shutdownCalled.get();
  }

  /**
   * Keeps the spans.
   *
   * @param exportedSpans the spans to export
   * @return success
   */
  @Override
  public CompletableResultCode export(Collection<SpanData> exportedSpans) {
    spans.addAll(exportedSpans);
    return CompletableResultCode.ofSuccess();
  }

  /**
   * Records that the exporter was flushed.
   *
   * @return success
   */
  @Override
  public CompletableResultCode flush() {
    flushCalled.set(true);
    return CompletableResultCode.ofSuccess();
  }

  /**
   * Records that the exporter was stopped.
   *
   * @return success
   */
  @Override
  public CompletableResultCode shutdown() {
    shutdownCalled.set(true);
    return CompletableResultCode.ofSuccess();
  }
}
