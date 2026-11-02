package com.exepex.stratum.observability;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.StratumModuleSwitch;
import com.exepex.stratum.core.properties.StratumDefaults;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.support.EnvironmentPostProcessorApplicationListener;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;

/**
 * Drops metrics and spans quietly when the module is on and their address is on this machine, such
 * as {@code http://localhost:4318/v1/metrics}. A developer often runs without a Collector, and
 * Micrometer and OpenTelemetry would otherwise log a stack trace at every failed send. Each address
 * is checked on its own, and a service's own {@code logging.level} wins.
 */
@StratumInternal
public class LocalCollectorLogging
    implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

  /** The log level that hides Micrometer's warning when it cannot push metrics. */
  static final String METRICS_EXPORT_LOG_LEVEL =
      "logging.level.io.micrometer.registry.otlp.OtlpMeterRegistry";

  /** The log level that hides OpenTelemetry's error when it cannot send spans over HTTP. */
  static final String SPAN_HTTP_EXPORT_LOG_LEVEL =
      "logging.level.io.opentelemetry.exporter.internal.http.HttpExporter";

  /** The log level that hides OpenTelemetry's error when it cannot send spans over gRPC. */
  static final String SPAN_GRPC_EXPORT_LOG_LEVEL =
      "logging.level.io.opentelemetry.exporter.internal.grpc.GrpcExporter";

  /** Each export address, with the log levels that hide its failed sends. */
  private static final Map<String, List<String>> LOG_LEVELS_BY_ADDRESS =
      Map.of(
          ObservabilityConstants.METRICS_EXPORT_URL,
          List.of(METRICS_EXPORT_LOG_LEVEL),
          ObservabilityConstants.TRACING_EXPORT_ENDPOINT,
          List.of(SPAN_HTTP_EXPORT_LOG_LEVEL, SPAN_GRPC_EXPORT_LOG_LEVEL));

  /** The level that turns a logger off. */
  private static final String OFF = "off";

  /** The name of this machine; an IP address is checked as a loopback address instead. */
  private static final String LOCALHOST = "localhost";

  /**
   * Runs after Spring Boot's environment post-processors, so an address from the {@code
   * OTEL_EXPORTER_OTLP_*} environment variables counts, and before Spring Boot applies the log
   * levels.
   *
   * @return the order
   */
  @Override
  public int getOrder() {
    return EnvironmentPostProcessorApplicationListener.DEFAULT_ORDER + 1;
  }

  /**
   * Turns off the failed-send logging of each export whose address is on this machine.
   *
   * @param event the event that carries the application's environment
   */
  @Override
  public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
    var environment = event.getEnvironment();
    if (!StratumModuleSwitch.isEnabled(environment, ObservabilityConstants.MODULE)) {
      return;
    }
    Map<String, Object> quietLogLevels =
        LOG_LEVELS_BY_ADDRESS.entrySet().stream()
            .filter(addressLogLevels -> isLocal(environment.getProperty(addressLogLevels.getKey())))
            .flatMap(addressLogLevels -> addressLogLevels.getValue().stream())
            .collect(Collectors.toMap(Function.identity(), logLevel -> OFF));
    StratumDefaults.addBelowServiceValues(environment, quietLogLevels);
  }

  /**
   * Returns whether the address names this machine: {@code localhost} or a loopback IP address,
   * such as {@code 127.0.0.1} or {@code [::1]}. A host name is never looked up, and an address that
   * cannot be read is not local.
   */
  private boolean isLocal(@Nullable String address) {
    if (address == null) {
      return false;
    }
    try {
      var host = URI.create(address).getHost();
      return host != null && (LOCALHOST.equals(host) || isLoopbackIpAddress(host));
    } catch (IllegalArgumentException unreadableAddress) {
      return false;
    }
  }

  /** Returns whether the host is a loopback IP address; a host name is not one. */
  private boolean isLoopbackIpAddress(String host) {
    try {
      return InetAddress.ofLiteral(host.replace("[", "").replace("]", "")).isLoopbackAddress();
    } catch (IllegalArgumentException hostName) {
      return false;
    }
  }
}
