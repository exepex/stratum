package com.exepex.stratum.data;

import static com.exepex.stratum.data.testsupport.NamedPoolTestData.BATCH;
import static com.exepex.stratum.data.testsupport.NamedPoolTestData.BATCH_KEYS;
import static com.exepex.stratum.data.testsupport.NamedPoolTestData.withVariables;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.core.error.StratumException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("LeakAlarmCheck checks each named pool's leak alarm as the pool is built")
class LeakAlarmCheckTest {

  /** The pool {@code batch}'s leak alarm threshold key. */
  private static final String THRESHOLD_KEY = BATCH_KEYS + "hikari.leak-detection-threshold";

  /** The pool {@code batch}'s connection lifetime key. */
  private static final String LIFETIME_KEY = BATCH_KEYS + "hikari.max-lifetime";

  /** The threshold's name in a pool's Java HikariCP settings. */
  private static final String THRESHOLD = "leak-detection-threshold";

  /** A threshold HikariCP turns off, since it is under 2 seconds. */
  private static final String TOO_LOW = "500";

  /** A threshold HikariCP keeps with its default lifetime. */
  private static final String KEPT = "5000";

  /**
   * Thresholds the pool {@code batch}'s key sets that HikariCP would turn off with its default
   * {@code max-lifetime} of 30 minutes.
   */
  private static final List<String> THRESHOLDS_TURNED_OFF = List.of("1999", "1800001");

  private final LeakAlarmCheck check = new LeakAlarmCheck();

  /** Checks the named pools of the settings resolved from the environment. */
  private void checkResolved(Environment environment) {
    check.checkNamedPools(environment, DataProperties.resolve(environment));
  }

  /** Checks the pool {@code batch} with the given Java HikariCP settings over the environment. */
  private void checkJava(Environment environment, Map<String, String> hikari) {
    var pool = PoolSettings.builder().hikari(hikari).build();
    var settings =
        DataProperties.resolve(environment).toBuilder().sources(Map.of(BATCH, pool)).build();
    check.checkNamedPools(environment, settings);
  }

  /** Checks that the check stops startup with {@code SX-DATA-1002} naming the given text. */
  private void assertStartupStops(Runnable run, String text) {
    assertThatThrownBy(run::run)
        .isInstanceOfSatisfying(
            StratumException.class,
            failure -> assertThat(failure.getErrorCode()).isEqualTo(DataErrorCode.LEAK_ALARM_OFF))
        .hasMessageContaining(text);
  }

  @Nested
  @DisplayName("with the pool's keys")
  class Keys {

    @ParameterizedTest(name = "threshold {0}")
    @FieldSource("com.exepex.stratum.data.LeakAlarmCheckTest#THRESHOLDS_TURNED_OFF")
    @DisplayName("stops startup when HikariCP would turn the alarm off")
    void stopsWhenTurnedOff(String threshold) {
      var environment = new MockEnvironment().withProperty(THRESHOLD_KEY, threshold);

      assertStartupStops(() -> checkResolved(environment), THRESHOLD_KEY);
    }

    @Test
    @DisplayName("stops startup for an operating system variable in Spring Boot's spelling")
    void stopsForVariable() {
      var environment =
          withVariables(
              Map.of("STRATUM_DATA_SOURCES_BATCH_HIKARI_LEAKDETECTIONTHRESHOLD", TOO_LOW));

      assertStartupStops(() -> checkResolved(environment), THRESHOLD_KEY);
    }

    @Test
    @DisplayName("checks the pool against its own max-lifetime, and names both keys")
    void checksOwnLifetime() {
      var environment =
          new MockEnvironment()
              .withProperty(THRESHOLD_KEY, "600000")
              .withProperty(LIFETIME_KEY, "300000");

      assertStartupStops(
          () -> checkResolved(environment), THRESHOLD_KEY + " is more than " + LIFETIME_KEY);
    }

    @Test
    @DisplayName("lets the pool start with a threshold under its own longer max-lifetime")
    void keepsLongerLifetime() {
      var environment =
          new MockEnvironment()
              .withProperty(THRESHOLD_KEY, "2400000")
              .withProperty(LIFETIME_KEY, "3600000");

      assertThatCode(() -> checkResolved(environment)).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("with the pool's Java settings")
  class JavaSettings {

    @Test
    @DisplayName("stops startup when HikariCP would turn the alarm off, with no key at all")
    void stopsWithoutKeys() {
      assertStartupStops(
          () -> checkJava(new MockEnvironment(), Map.of(THRESHOLD, TOO_LOW)),
          THRESHOLD_KEY + " is under 2000 milliseconds");
    }

    @Test
    @DisplayName("checks a Java threshold against the lifetime the pool's key sets")
    void checksKeyLifetime() {
      var environment = new MockEnvironment().withProperty(LIFETIME_KEY, "3000");

      assertStartupStops(
          () -> checkJava(environment, Map.of(THRESHOLD, KEPT)),
          THRESHOLD_KEY + " is more than " + LIFETIME_KEY);
    }

    @Test
    @DisplayName("win over the pool's key that HikariCP would turn off")
    void winOverKey() {
      var environment = new MockEnvironment().withProperty(THRESHOLD_KEY, TOO_LOW);

      assertThatCode(() -> checkJava(environment, Map.of(THRESHOLD, KEPT)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("that HikariCP would turn off stop startup over a key it keeps")
    void stopOverKey() {
      var environment = new MockEnvironment().withProperty(THRESHOLD_KEY, KEPT);

      assertStartupStops(() -> checkJava(environment, Map.of(THRESHOLD, TOO_LOW)), THRESHOLD_KEY);
    }
  }
}
