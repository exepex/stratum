package com.exepex.stratum.observability;

import com.exepex.stratum.core.error.ErrorReport;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;

/**
 * Keeps the number of time series bounded, so a tag that holds many values cannot overload the
 * metrics backend. It drops every new meter once the registry holds {@code max-meters}, and every
 * new HTTP client meter once {@code max-client-names} hosts have been seen. Each cap logs {@code
 * SX-OBSERVABILITY-1003} once, naming the meter, never a tag value. A removed meter frees its slot.
 */
@Slf4j
public class MeterCountGuard implements MeterFilter, MeterRegistryCustomizer<MeterRegistry> {

  /** The settings key named in the warning when the total cap is reached. */
  private static final String MAX_METERS_KEY =
      ObservabilityConstants.PREFIX + ".metrics.max-meters";

  /** The settings key named in the warning when the client host cap is reached. */
  private static final String MAX_CLIENT_NAMES_KEY =
      ObservabilityConstants.PREFIX + ".metrics.max-client-names";

  /** The most meters a registry may hold. */
  private final int maxMeters;

  /** The meters the registries hold, as this guard let them in. */
  private final Set<Meter.Id> heldMeterIds = ConcurrentHashMap.newKeySet();

  /** Whether the total cap has been reported, so it is reported once. */
  private final AtomicBoolean maxMetersReported = new AtomicBoolean();

  /** Whether the client host cap has been reported, so it is reported once. */
  private final AtomicBoolean maxClientNamesReported = new AtomicBoolean();

  /** Micrometer's cap on the {@code client.name} values of the HTTP client meters. */
  private final MeterFilter clientNameCap;

  /**
   * Makes a guard with the two caps.
   *
   * @param maxMeters the most meters a registry may hold, from {@code
   *     stratum.observability.metrics.max-meters}
   * @param maxClientNames the most {@code client.name} values, from {@code
   *     stratum.observability.metrics.max-client-names}
   * @param clientMeterName the name of the HTTP client meters, from Spring Boot's {@code
   *     management.observations.http.client.requests.name}
   */
  public MeterCountGuard(int maxMeters, int maxClientNames, String clientMeterName) {
    this.maxMeters = maxMeters;
    clientNameCap =
        MeterFilter.maximumAllowableTags(
            clientMeterName,
            ObservabilityConstants.CLIENT_NAME_TAG,
            maxClientNames,
            MeterFilter.deny(
                id ->
                    reportCapReached(maxClientNamesReported, id.getName(), MAX_CLIENT_NAMES_KEY)));
  }

  /**
   * Frees a meter's slot when the registry removes it, as Kafka's metrics do on every rebalance.
   *
   * @param registry each registry Spring Boot makes
   */
  @Override
  public void customize(MeterRegistry registry) {
    registry.config().onMeterRemoved(meter -> heldMeterIds.remove(meter.getId()));
  }

  /**
   * Lets a new meter in while both caps allow it. Micrometer calls this only for a meter it does
   * not hold yet, under its own lock.
   *
   * @param id the meter's name and tags
   * @return {@code DENY} past a cap, otherwise {@code NEUTRAL}
   */
  @Override
  public MeterFilterReply accept(Meter.Id id) {
    if (clientNameCap.accept(id) == MeterFilterReply.DENY) {
      return MeterFilterReply.DENY;
    }
    if (heldMeterIds.contains(id)) {
      return MeterFilterReply.NEUTRAL;
    }
    if (heldMeterIds.size() < maxMeters) {
      heldMeterIds.add(id);
      return MeterFilterReply.NEUTRAL;
    }
    reportCapReached(maxMetersReported, id.getName(), MAX_METERS_KEY);
    return MeterFilterReply.DENY;
  }

  /**
   * Logs the warning for a cap the first time it drops a meter, naming the meter but never a value.
   *
   * @return {@code true}, so it can be a condition of {@link MeterFilter#deny}
   */
  private boolean reportCapReached(AtomicBoolean reported, String meterName, String key) {
    if (reported.compareAndSet(false, true) && log.isWarnEnabled()) {
      var report =
          ErrorReport.of(
              ObservabilityErrorCode.METER_CAP_REACHED, "meter " + meterName + ", key " + key);
      // The report already turns line breaks into spaces; SpotBugs only sees this replacement.
      log.warn(report.getCodeAndMessage().replaceAll("[\r\n]", " "));
    }
    return true;
  }
}
