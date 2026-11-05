package com.exepex.stratum.observability;

import com.exepex.stratum.core.api.StratumInternal;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import lombok.RequiredArgsConstructor;

/**
 * Sends spans without the exception's type, message or stack trace. A failed span keeps its {@code
 * ERROR} status; the exception's details stay in the service's logs, found by the trace ID.
 */
@StratumInternal
@RequiredArgsConstructor
public class ExceptionFreeSpanExporter implements SpanExporter {

  /** The exporter that sends the spans, such as Spring Boot's OTLP exporter. */
  private final SpanExporter delegate;

  /**
   * Sends the spans, each without its exception data. A span that holds none is sent as it is.
   *
   * @param spans the finished spans
   * @return the result of the wrapped exporter
   */
  @Override
  public CompletableResultCode export(Collection<SpanData> spans) {
    return delegate.export(spans.stream().map(ExceptionFreeSpanData::from).toList());
  }

  /**
   * Sends the spans the wrapped exporter still holds.
   *
   * @return the result of the wrapped exporter
   */
  @Override
  public CompletableResultCode flush() {
    return delegate.flush();
  }

  /**
   * Stops the wrapped exporter.
   *
   * @return the result of the wrapped exporter
   */
  @Override
  public CompletableResultCode shutdown() {
    return delegate.shutdown();
  }
}
