package com.exepex.stratum.observability.testsupport;

import com.exepex.stratum.observability.MeterCountGuard;
import com.exepex.stratum.observability.ObservabilityConstants;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.experimental.UtilityClass;

/** Registries guarded by a {@link MeterCountGuard}, and meters to fill them, for the cap tests. */
@UtilityClass
public class MeterCountTestData {

  /** The tag of the order counters, whose every value makes a new meter. */
  public static final String ORDER_TAG = "order";

  /** The code the guard logs when a cap is reached. */
  private static final Pattern CAP_REACHED_CODE = Pattern.compile("SX-OBSERVABILITY-1003");

  /**
   * Returns how many times a log holds the cap warning.
   *
   * @param log the captured log
   * @return the number of warnings
   */
  public static long capWarningsIn(String log) {
    return CAP_REACHED_CODE.matcher(log).results().count();
  }

  /**
   * Returns a registry that the guard caps, set up as Spring Boot sets up each registry.
   *
   * @param guard the guard under test
   * @return the registry
   */
  public static MeterRegistry registryGuardedBy(MeterCountGuard guard) {
    var registry = new SimpleMeterRegistry();
    guard.customize(registry);
    registry.config().meterFilter(guard);
    return registry;
  }

  /**
   * Registers counters with distinct order numbers, the kind of tag that makes a new meter each.
   *
   * @param registry the registry to fill
   * @param count how many counters to register
   */
  public static void registerOrderCounters(MeterRegistry registry, int count) {
    IntStream.range(0, count)
        .forEach(
            order -> registry.counter(ObservabilityTestData.METRIC_NAME, ORDER_TAG, "o-" + order));
  }

  /**
   * Registers one HTTP client timer for each of a number of hosts, as Spring's clients do.
   *
   * @param registry the registry to fill
   * @param hostCount how many hosts to call
   */
  public static void registerClientTimers(MeterRegistry registry, int hostCount) {
    registerClientTimers(registry, ObservabilityConstants.DEFAULT_CLIENT_REQUESTS_NAME, hostCount);
  }

  /**
   * Registers one HTTP client timer under a meter name for each of a number of hosts.
   *
   * @param registry the registry to fill
   * @param clientMeterName the name of the HTTP client meters
   * @param hostCount how many hosts to call
   */
  public static void registerClientTimers(
      MeterRegistry registry, String clientMeterName, int hostCount) {
    IntStream.range(0, hostCount)
        .forEach(
            host ->
                registry.timer(
                    clientMeterName,
                    ObservabilityConstants.CLIENT_NAME_TAG,
                    "partner-" + host + ".example.com"));
  }

  /**
   * Returns the hosts the HTTP client meters of a registry hold.
   *
   * @param registry the registry
   * @return the distinct {@code client.name} values
   */
  public static Set<String> clientNamesIn(MeterRegistry registry) {
    return clientNamesIn(registry, ObservabilityConstants.DEFAULT_CLIENT_REQUESTS_NAME);
  }

  /**
   * Returns the hosts the HTTP client meters of a meter name hold in a registry.
   *
   * @param registry the registry
   * @param clientMeterName the name of the HTTP client meters
   * @return the distinct {@code client.name} values
   */
  public static Set<String> clientNamesIn(MeterRegistry registry, String clientMeterName) {
    return registry.find(clientMeterName).meters().stream()
        .map(meter -> meter.getId().getTag(ObservabilityConstants.CLIENT_NAME_TAG))
        .collect(Collectors.toSet());
  }
}
