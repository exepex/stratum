package com.exepex.stratum.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.exepex.stratum.core.error.StratumException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.batch.core.job.Job;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;

@DisplayName(
    "DataEnvironmentPostProcessor gives the pool and transaction settings Stratum's defaults")
class DataEnvironmentPostProcessorTest {

  // The keys are spelled out here, so a misspelt key in the module fails the tests.
  private static final String CONNECTION_TIMEOUT = "spring.datasource.hikari.connection-timeout";

  private static final String TRANSACTION_TIMEOUT = "spring.transaction.default-timeout";

  private static final String STATEMENT_TIMEOUT_STATUS = "stratum.web.error.statuses[SX-DATA-1003]";

  private static final String LEAK_THRESHOLD = "spring.datasource.hikari.leak-detection-threshold";

  private static final String MAX_LIFETIME = "spring.datasource.hikari.max-lifetime";

  /** HikariCP's own {@code max-lifetime}, in milliseconds: 30 minutes. */
  private static final String HIKARI_MAX_LIFETIME = "1800000";

  private static final String BACKWARD_COMPATIBLE = "stratum.backward-compatible";

  private static final String ON = "true";

  private static final String OFF = "false";

  /** The defaults that apply in both modes, with Stratum's values. */
  private static final Map<String, String> INTERNAL_DEFAULTS = Map.of(CONNECTION_TIMEOUT, "3000");

  /** The defaults that apply only with {@code stratum.backward-compatible=false}. */
  private static final Map<String, String> EXTERNAL_DEFAULTS =
      Map.of(TRANSACTION_TIMEOUT, "60s", STATEMENT_TIMEOUT_STATUS, "503");

  /** Every key the module gives a default, with a value a service sets instead. */
  private static final List<Arguments> SERVICE_VALUES =
      List.of(
          arguments(CONNECTION_TIMEOUT, "10000"),
          arguments(TRANSACTION_TIMEOUT, "300s"),
          arguments(STATEMENT_TIMEOUT_STATUS, "500"));

  /** Thresholds HikariCP would turn off with its default {@code max-lifetime} of 30 minutes. */
  private static final List<Arguments> THRESHOLDS_TURNED_OFF =
      List.of(
          arguments(LEAK_THRESHOLD, "1999"),
          arguments(LEAK_THRESHOLD, "1800001"),
          arguments("spring.datasource.hikari.leakDetectionThreshold", "1000"));

  /** Thresholds and lifetimes HikariCP keeps the alarm on with, or that turn it off on purpose. */
  private static final List<Arguments> THRESHOLDS_KEPT =
      List.of(
          arguments("2000", HIKARI_MAX_LIFETIME),
          arguments(HIKARI_MAX_LIFETIME, HIKARI_MAX_LIFETIME),
          arguments("0", HIKARI_MAX_LIFETIME),
          arguments("3600000", "0"));

  private final DataEnvironmentPostProcessor postProcessor = new DataEnvironmentPostProcessor();

  /** An application without Spring Batch on its class path. */
  private final SpringApplication applicationWithoutBatch =
      new SpringApplication(new DefaultResourceLoader(new FilteredClassLoader(Job.class)));

  /** Runs the post-processor for an application without Spring Batch and returns the result. */
  private MockEnvironment runPostProcessor(MockEnvironment environment) {
    postProcessor.postProcessEnvironment(environment, applicationWithoutBatch);
    return environment;
  }

  /** Checks that each key has its value in the environment. */
  private void assertSet(MockEnvironment environment, Map<String, String> expected) {
    expected.forEach((key, value) -> assertThat(environment.getProperty(key)).isEqualTo(value));
  }

  /** Checks that none of the keys has a value in the environment. */
  private void assertUnset(MockEnvironment environment, Iterable<String> keys) {
    keys.forEach(key -> assertThat(environment.getProperty(key)).as(key).isNull());
  }

  @Nested
  @DisplayName("with the module off")
  class WithModuleOff {

    @Test
    @DisplayName("it sets nothing")
    void setsNothing() {
      var environment =
          runPostProcessor(new MockEnvironment().withProperty(DataConstants.ENABLED, OFF));

      assertUnset(environment, INTERNAL_DEFAULTS.keySet());
      assertUnset(environment, EXTERNAL_DEFAULTS.keySet());
    }

    @Test
    @DisplayName("it checks no leak alarm threshold")
    void checksNoThreshold() {
      var environment =
          new MockEnvironment()
              .withProperty(DataConstants.ENABLED, OFF)
              .withProperty(LEAK_THRESHOLD, "1000");

      assertThatNoException().isThrownBy(() -> runPostProcessor(environment));
    }
  }

  @Nested
  @DisplayName("in strict mode, the default")
  class InStrictMode {

    @Test
    @DisplayName("it sets every default")
    void setsEveryDefault() {
      var environment = runPostProcessor(new MockEnvironment());

      assertSet(environment, INTERNAL_DEFAULTS);
      assertSet(environment, EXTERNAL_DEFAULTS);
    }

    @ParameterizedTest(name = "{0}={1}")
    @FieldSource("com.exepex.stratum.data.DataEnvironmentPostProcessorTest#SERVICE_VALUES")
    @DisplayName("the service's own value wins")
    void keepsServiceValue(String key, String serviceValue) {
      var environment = runPostProcessor(new MockEnvironment().withProperty(key, serviceValue));

      assertThat(environment.getProperty(key)).isEqualTo(serviceValue);
    }

    @Test
    @DisplayName("a value the service sets in Java as a default property wins")
    void keepsServiceDefaultProperty() {
      var environment = new MockEnvironment();
      DefaultPropertiesPropertySource.addOrMerge(
          Map.of(CONNECTION_TIMEOUT, "10000"), environment.getPropertySources());

      runPostProcessor(environment);

      assertThat(environment.getProperty(CONNECTION_TIMEOUT)).isEqualTo("10000");
    }

    @Test
    @DisplayName(
        "with Spring Batch present, it sets no transaction timeout and every other default")
    void setsNoTransactionTimeoutWithSpringBatch() {
      var environment = new MockEnvironment();

      postProcessor.postProcessEnvironment(environment, new SpringApplication());

      assertUnset(environment, List.of(TRANSACTION_TIMEOUT));
      assertSet(environment, INTERNAL_DEFAULTS);
      assertSet(environment, Map.of(STATEMENT_TIMEOUT_STATUS, "503"));
    }
  }

  @Nested
  @DisplayName("in backward-compatible mode")
  class InBackwardCompatibleMode {

    @Test
    @DisplayName("it sets only the defaults consumers cannot see")
    void setsOnlyInternalDefaults() {
      var environment =
          runPostProcessor(new MockEnvironment().withProperty(BACKWARD_COMPATIBLE, ON));

      assertSet(environment, INTERNAL_DEFAULTS);
      assertUnset(environment, EXTERNAL_DEFAULTS.keySet());
    }
  }

  @Nested
  @DisplayName("the leak alarm")
  class LeakAlarmThreshold {

    /** Checks that the post-processor stops startup with {@code SX-DATA-1002}. */
    private void assertStartupStops(MockEnvironment environment) {
      assertThatThrownBy(() -> runPostProcessor(environment))
          .isInstanceOfSatisfying(
              StratumException.class,
              failure ->
                  assertThat(failure.getErrorCode()).isEqualTo(DataErrorCode.LEAK_ALARM_OFF));
    }

    @Test
    @DisplayName("stays off unless the service sets a threshold, in both modes")
    void staysOffByDefault() {
      var strict = runPostProcessor(new MockEnvironment());
      var compatible =
          runPostProcessor(new MockEnvironment().withProperty(BACKWARD_COMPATIBLE, ON));

      assertUnset(strict, List.of(LEAK_THRESHOLD));
      assertUnset(compatible, List.of(LEAK_THRESHOLD));
    }

    @ParameterizedTest(name = "{0}={1}")
    @FieldSource("com.exepex.stratum.data.DataEnvironmentPostProcessorTest#THRESHOLDS_TURNED_OFF")
    @DisplayName("stops startup when HikariCP would turn it off")
    void stopsStartupWhenTurnedOff(String key, String threshold) {
      assertStartupStops(new MockEnvironment().withProperty(key, threshold));
    }

    @Test
    @DisplayName("stops startup naming both keys when it is above max-lifetime")
    void namesBothKeysAboveMaxLifetime() {
      var environment =
          new MockEnvironment()
              .withProperty(LEAK_THRESHOLD, "600000")
              .withProperty(MAX_LIFETIME, "300000");

      assertThatThrownBy(() -> runPostProcessor(environment))
          .isInstanceOf(StratumException.class)
          .hasMessageContaining(LEAK_THRESHOLD + " is more than " + MAX_LIFETIME);
    }

    @ParameterizedTest(name = "threshold {0}, max-lifetime {1}")
    @FieldSource("com.exepex.stratum.data.DataEnvironmentPostProcessorTest#THRESHOLDS_KEPT")
    @DisplayName("lets the application start when HikariCP keeps it, or it is off")
    void startsWhenKept(String threshold, String maxLifetime) {
      var environment =
          new MockEnvironment()
              .withProperty(LEAK_THRESHOLD, threshold)
              .withProperty(MAX_LIFETIME, maxLifetime);

      assertThatNoException().isThrownBy(() -> runPostProcessor(environment));
    }
  }
}
