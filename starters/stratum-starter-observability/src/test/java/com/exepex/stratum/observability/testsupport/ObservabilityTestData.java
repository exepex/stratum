package com.exepex.stratum.observability.testsupport;

import com.exepex.stratum.observability.ObservabilityConstants;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import java.util.List;
import lombok.experimental.UtilityClass;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.ClassUtils;

/** Sample metrics and environments, and the clean-up, shared by the observability tests. */
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
   * Returns an environment with the module switched on and nothing else set.
   *
   * @return the environment, which a test can add properties to
   */
  public static MockEnvironment environmentWithModuleOn() {
    return new MockEnvironment().withProperty(ObservabilityConstants.ENABLED, "true");
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
