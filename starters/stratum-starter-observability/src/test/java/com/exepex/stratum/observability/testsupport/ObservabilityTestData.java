package com.exepex.stratum.observability.testsupport;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import java.util.List;
import lombok.experimental.UtilityClass;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.util.ClassUtils;

/** Sample metrics, spans and environments, and the clean-up, shared by the observability tests. */
@UtilityClass
public class ObservabilityTestData {

  /** The name of every sample metric. */
  public static final String METRIC_NAME = "orders.placed";

  /**
   * The loggers of Micrometer's OTLP registry and of OpenTelemetry's span exporters over HTTP and
   * gRPC, which Stratum turns off for a Collector on this machine.
   */
  private static final List<String> EXPORT_LOGGERS =
      List.of(
          "io.micrometer.registry.otlp.OtlpMeterRegistry",
          "io.opentelemetry.exporter.internal.http.HttpExporter",
          "io.opentelemetry.exporter.internal.grpc.GrpcExporter");

  /** The trace ID of {@link #SAMPLED_SPAN}, in W3C form. */
  public static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  /** A sampled span made with OpenTelemetry's own API, as a context carries it. */
  public static final Span SAMPLED_SPAN =
      Span.wrap(
          SpanContext.create(
              TRACE_ID, "00f067aa0ba902b7", TraceFlags.getSampled(), TraceState.getDefault()));

  /** The value of the sample tag, made up, which the guard must never report. */
  public static final String TAG_VALUE = "user-7731";

  /**
   * Returns a counter's name and tags, with one tag.
   *
   * @param tagName the tag's name; its value is {@link #TAG_VALUE}
   * @return the counter's ID, as a meter filter sees it
   */
  public static Meter.Id counterTaggedWith(String tagName) {
    return new Meter.Id(METRIC_NAME, Tags.of(tagName, TAG_VALUE), null, null, Meter.Type.COUNTER);
  }

  /**
   * Clears the levels Stratum set on the export loggers, because they outlive the application in
   * the test JVM and would hide a later test's failed sends.
   */
  public static void resetExportLogLevels() {
    var loggingSystem = LoggingSystem.get(ClassUtils.getDefaultClassLoader());
    EXPORT_LOGGERS.forEach(logger -> loggingSystem.setLogLevel(logger, null));
  }
}
