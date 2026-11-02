package com.exepex.stratum.observability;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.StratumModuleSwitch;
import com.exepex.stratum.core.error.StratumException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.support.EnvironmentPostProcessorApplicationListener;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;

/**
 * Stops startup with {@code SX-OBSERVABILITY-1001} when the module is on and an OTLP export that is
 * on has no address, in every profile. With OpenTelemetry off ({@code
 * management.opentelemetry.enabled=false} or {@code OTEL_SDK_DISABLED=true}), spans are not sent,
 * so only the metrics address is needed. It runs after every environment post-processor, so an
 * address that Spring Boot maps from the {@code OTEL_EXPORTER_OTLP_*} environment variables counts.
 */
@StratumInternal
public class ObservabilityExportAddressCheck
    implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

  /**
   * Each export address, with the switches that decide whether it is used, most specific first. As
   * in Spring Boot, the first switch that is set decides, and an export with no switch set is on.
   */
  private static final Map<String, List<String>> EXPORT_SWITCHES_BY_ADDRESS =
      Map.of(
          ObservabilityConstants.METRICS_EXPORT_URL,
          List.of(
              ObservabilityConstants.METRICS_EXPORT_ENABLED,
              ObservabilityConstants.METRICS_DEFAULT_EXPORT_ENABLED),
          ObservabilityConstants.TRACING_EXPORT_ENDPOINT,
          List.of(
              ObservabilityConstants.TRACING_OTLP_EXPORT_ENABLED,
              ObservabilityConstants.TRACING_EXPORT_ENABLED));

  /**
   * Runs right after Spring Boot's environment post-processors, so every property is final.
   *
   * @return the order
   */
  @Override
  public int getOrder() {
    return EnvironmentPostProcessorApplicationListener.DEFAULT_ORDER + 1;
  }

  /**
   * Checks the export addresses once the environment is ready.
   *
   * @param event the event that carries the application's environment
   * @throws StratumException {@code SX-OBSERVABILITY-1001} if the module is on and an export that
   *     is on has no address
   */
  @Override
  public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
    var environment = event.getEnvironment();
    if (!StratumModuleSwitch.isEnabled(environment, ObservabilityConstants.MODULE)) {
      return;
    }
    var missingAddresses =
        EXPORT_SWITCHES_BY_ADDRESS.entrySet().stream()
            .filter(exportSwitches -> isOn(environment, exportSwitches.getValue()))
            .map(Map.Entry::getKey)
            .filter(address -> spansCanBeSent(environment) || !isSpanAddress(address))
            .filter(address -> !StringUtils.hasText(environment.getProperty(address)))
            .sorted()
            .toList();
    if (!missingAddresses.isEmpty()) {
      throw new StratumException(
          ObservabilityErrorCode.EXPORT_ENDPOINT_MISSING,
          "set " + String.join(" and ", missingAddresses) + ", or turn that export off");
    }
  }

  /** Returns whether OpenTelemetry is on; when it is off, Spring Boot sends no spans. */
  private boolean spansCanBeSent(ConfigurableEnvironment environment) {
    return environment.getProperty(
        ObservabilityConstants.OPEN_TELEMETRY_ENABLED, Boolean.class, true);
  }

  /** Returns whether the address is the one spans are sent to. */
  private boolean isSpanAddress(String address) {
    return ObservabilityConstants.TRACING_EXPORT_ENDPOINT.equals(address);
  }

  /** Returns the first switch that is set, most specific first, or on when none is set. */
  private boolean isOn(
      ConfigurableEnvironment environment, List<String> switchesMostSpecificFirst) {
    return switchesMostSpecificFirst.stream()
        .flatMap(
            exportSwitch ->
                Optional.ofNullable(environment.getProperty(exportSwitch, Boolean.class)).stream())
        .findFirst()
        .orElse(true);
  }
}
