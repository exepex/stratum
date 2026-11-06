package com.exepex.stratum.observability;

import static com.exepex.stratum.observability.testsupport.MeterCountTestData.ORDER_TAG;
import static com.exepex.stratum.observability.testsupport.MeterCountTestData.capWarningsIn;
import static com.exepex.stratum.observability.testsupport.MeterCountTestData.clientNamesIn;
import static com.exepex.stratum.observability.testsupport.MeterCountTestData.registerClientTimers;
import static com.exepex.stratum.observability.testsupport.MeterCountTestData.registerOrderCounters;
import static com.exepex.stratum.observability.testsupport.MeterCountTestData.registryGuardedBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.observability.testsupport.ObservabilityTestData;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@DisplayName("MeterCountGuard keeps the number of meters and client hosts under their caps")
@ExtendWith(OutputCaptureExtension.class)
class MeterCountGuardTest {

  /** A small total cap, so a test fills it quickly. */
  private static final int MAX_METERS = 5;

  /** Stratum's default cap on the client hosts (owner decision Q5). */
  private static final int MAX_CLIENT_NAMES = 100;

  /** The order of the first counter registered. */
  private static final String FIRST_ORDER = "o-0";

  /** A total cap high enough that only the client host cap is reached. */
  private static final int MANY_METERS = 10_000;

  @Nested
  @DisplayName("with the total cap")
  class TotalCap {

    private final MeterCountGuard guard =
        new MeterCountGuard(
            MAX_METERS, MAX_CLIENT_NAMES, ObservabilityConstants.DEFAULT_CLIENT_REQUESTS_NAME);

    private final MeterRegistry registry = registryGuardedBy(guard);

    @Test
    @DisplayName("twice the cap registers exactly the cap and logs one warning, with no tag value")
    void holdsCapAndWarnsOnce(CapturedOutput output) {
      registerOrderCounters(registry, 2 * MAX_METERS);

      assertThat(registry.getMeters()).hasSize(MAX_METERS);
      assertThat(capWarningsIn(output.getOut())).isOne();
      assertThat(output.getOut())
          .contains("meter " + ObservabilityTestData.METRIC_NAME)
          .contains(ObservabilityConstants.PREFIX + ".metrics.max-meters")
          .doesNotContain("o-" + MAX_METERS);
    }

    @Test
    @DisplayName("a meter held before the cap is reached keeps counting after it")
    void heldMeterKeepsCounting() {
      registerOrderCounters(registry, 2 * MAX_METERS);

      registry.counter(ObservabilityTestData.METRIC_NAME, ORDER_TAG, FIRST_ORDER).increment();

      assertThat(
              registry
                  .get(ObservabilityTestData.METRIC_NAME)
                  .tag(ORDER_TAG, FIRST_ORDER)
                  .counter()
                  .count())
          .isOne();
    }

    @Test
    @DisplayName("a removed meter frees its slot, as after a Kafka rebalance")
    void removedMeterFreesSlot() {
      registerOrderCounters(registry, MAX_METERS);
      registry.remove(registry.getMeters().getFirst());

      registry.counter(ObservabilityTestData.METRIC_NAME, ORDER_TAG, "after-rebalance");

      assertThat(
              registry
                  .find(ObservabilityTestData.METRIC_NAME)
                  .tag(ORDER_TAG, "after-rebalance")
                  .counter())
          .isNotNull();
    }

    @Test
    @DisplayName("two registries sharing the guard hold the same meters")
    void sharedRegistriesHoldSameMeters() {
      var second = registryGuardedBy(guard);

      registerOrderCounters(registry, 2 * MAX_METERS);
      registerOrderCounters(second, 2 * MAX_METERS);

      assertThat(second.getMeters())
          .extracting(Meter::getId)
          .containsExactlyInAnyOrderElementsOf(
              registry.getMeters().stream().map(Meter::getId).toList());
    }
  }

  @Nested
  @DisplayName("with the client host cap")
  class ClientHostCap {

    private final MeterCountGuard guard =
        new MeterCountGuard(
            MANY_METERS, MAX_CLIENT_NAMES, ObservabilityConstants.DEFAULT_CLIENT_REQUESTS_NAME);

    @Test
    @DisplayName("200 hosts leave 100 client.name values and log one warning")
    void holdsHostCapAndWarnsOnce(CapturedOutput output) {
      var registry = registryGuardedBy(guard);

      registerClientTimers(registry, 2 * MAX_CLIENT_NAMES);

      assertThat(clientNamesIn(registry)).hasSize(MAX_CLIENT_NAMES);
      assertThat(capWarningsIn(output.getOut())).isOne();
      assertThat(output.getOut())
          .contains(ObservabilityConstants.PREFIX + ".metrics.max-client-names")
          .doesNotContain("partner-" + MAX_CLIENT_NAMES);
    }
  }
}
