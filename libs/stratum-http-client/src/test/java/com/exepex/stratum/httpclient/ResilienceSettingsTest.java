package com.exepex.stratum.httpclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.exepex.stratum.httpclient.testsupport.HttpClientTestData;
import com.exepex.stratum.httpclient.testsupport.SettingsChecks;
import com.exepex.stratum.httpclient.testsupport.TightResilienceSettings;
import com.exepex.stratum.test.StarterTestSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.context.properties.bind.BindException;

@DisplayName("A client's retry and circuit-breaker groups come from files and Java, key by key")
class ResilienceSettingsTest {

  /** The end of a setting of a longest wait of 1s, after a retry group's prefix. */
  private static final String MAX_DELAY_OF_1S = ".max-delay=1s";

  /** The end of a setting that turns the slow-call rule on at 2s, after a breaker's prefix. */
  private static final String SLOW_CALL_OF_2S = ".slow-call-duration-threshold=2s";

  /** The end of a setting of a slow-call rate of 50%, after a breaker's prefix. */
  private static final String SLOW_CALL_RATE_OF_50 = ".slow-call-rate-threshold=50";

  /** The rule every duration breaks when it is 0 or less. */
  private static final String DURATIONS_POSITIVE = "durations-positive";

  @Nested
  @DisplayName("from properties and YAML")
  class Binding {

    @Test
    @DisplayName("every retry and circuit-breaker key is read from properties")
    void everyKeyFromProperties() {
      var environment =
          SettingsChecks.environmentOf(
              HttpClientTestData.INVENTORY_RETRY + ".max-retries=2",
              HttpClientTestData.INVENTORY_RETRY + ".backoff-policy=exponential",
              HttpClientTestData.INVENTORY_RETRY + ".delay=200ms",
              HttpClientTestData.INVENTORY_RETRY + ".multiplier=3",
              HttpClientTestData.INVENTORY_RETRY + MAX_DELAY_OF_1S,
              HttpClientTestData.INVENTORY_BREAKER + ".enabled=true",
              HttpClientTestData.INVENTORY_BREAKER + ".failure-rate-threshold=10",
              HttpClientTestData.INVENTORY_BREAKER + ".sliding-window-size=20",
              HttpClientTestData.INVENTORY_BREAKER + ".minimum-number-of-calls=10",
              HttpClientTestData.INVENTORY_BREAKER + ".wait-duration-in-open-state=30s",
              HttpClientTestData.INVENTORY_BREAKER
                  + ".permitted-number-of-calls-in-half-open-state=3",
              HttpClientTestData.INVENTORY_BREAKER + SLOW_CALL_OF_2S,
              HttpClientTestData.INVENTORY_BREAKER + SLOW_CALL_RATE_OF_50);

      var settings =
          HttpClientProperties.resolve(environment).clientSettings(HttpClientTestData.INVENTORY);

      assertThat(settings.retry()).isEqualTo(TightResilienceSettings.PAYMENTS_RETRY);
      assertThat(settings.circuitBreaker()).isEqualTo(TightResilienceSettings.PAYMENTS_BREAKER);
    }

    @Test
    @DisplayName("both groups are read from a .yml file, each key falling back on its own")
    void groupsFromYaml() throws IOException {
      var settings =
          HttpClientProperties.resolve(HttpClientTestData.yamlEnvironment())
              .clientSettings(HttpClientTestData.INVENTORY);

      assertThat(settings.retry())
          .isEqualTo(
              HttpClientTestData.FLAKY_RETRY.toBuilder()
                  .delay(HttpClientTestData.SLOW_RETRY_DELAY)
                  .multiplier(HttpClientConstants.DEFAULT_MULTIPLIER)
                  .build());
      assertThat(settings.circuitBreaker())
          .isEqualTo(
              HttpClientTestData.STRICT_BREAKER.orElse(
                  HttpClientTestData.STRATUM_CIRCUIT_BREAKER.toBuilder()
                      .waitDurationInOpenState(HttpClientTestData.SHORT_OPEN_STATE)
                      .build()));
    }

    @Test
    @DisplayName(
        "a backoff-policy that is neither value stops startup naming the key, not the value")
    void unknownBackoffPolicy() {
      var key = HttpClientTestData.INVENTORY_RETRY + ".backoff-policy";
      var environment = SettingsChecks.environmentOf(key + "=linear");

      assertThatExceptionOfType(BindException.class)
          .isThrownBy(() -> HttpClientProperties.resolve(environment))
          .withMessageContaining("'" + key + "'")
          .withMessageNotContaining("linear");
    }

    @Test
    @DisplayName("the old retries and retry-delay are ignored, as any unknown key is")
    void oldRetryKeysIgnored() {
      var environment =
          SettingsChecks.environmentOf(
              HttpClientTestData.INVENTORY_SETTINGS + ".retries=0",
              HttpClientTestData.INVENTORY_SETTINGS + ".retry-delay=5s");

      assertThat(
              HttpClientProperties.resolve(environment)
                  .clientSettings(HttpClientTestData.INVENTORY)
                  .retry())
          .isEqualTo(HttpClientTestData.STRATUM_RETRY);
    }

    @Test
    @DisplayName("the old circuit-breaker: false, a value where the group goes, stops startup")
    void oldCircuitBreakerSwitch() {
      var key = HttpClientTestData.INVENTORY_BREAKER;
      var environment = SettingsChecks.environmentOf(key + "=false");

      assertThatExceptionOfType(BindException.class)
          .isThrownBy(() -> HttpClientProperties.resolve(environment))
          .withMessageContaining("'" + key + "'");
    }
  }

  @Nested
  @DisplayName("falling back")
  class Fallback {

    @Test
    @DisplayName("a client's own keys apply with the shared ones it leaves unset, then Stratum's")
    void keysFallBackOneByOne() {
      var environment =
          SettingsChecks.environmentOf(
              HttpClientTestData.DEFAULTS_RETRY + ".max-retries=2",
              HttpClientTestData.DEFAULTS_BREAKER + ".wait-duration-in-open-state=30s",
              HttpClientTestData.INVENTORY_RETRY + ".delay=100ms",
              HttpClientTestData.INVENTORY_BREAKER + ".failure-rate-threshold=10");

      var settings =
          HttpClientProperties.resolve(environment).clientSettings(HttpClientTestData.INVENTORY);

      assertThat(settings.retry())
          .isEqualTo(
              HttpClientTestData.STRATUM_RETRY.toBuilder()
                  .maxRetries(TightResilienceSettings.SHARED_MAX_RETRIES)
                  .delay(TightResilienceSettings.SHARED_DELAY)
                  .build());
      assertThat(settings.circuitBreaker())
          .isEqualTo(
              HttpClientTestData.STRATUM_CIRCUIT_BREAKER.toBuilder()
                  .failureRateThreshold(TightResilienceSettings.PAYMENTS_FAILURE_RATE)
                  .waitDurationInOpenState(TightResilienceSettings.SHARED_WAIT_IN_OPEN_STATE)
                  .build());
    }

    @Test
    @DisplayName(
        "a customizer that changes defaults and one client wins over the files, and the client"
            + " takes its unset keys from the new defaults")
    void javaSettingsApply(@TempDir Path folder) throws IOException {
      var environment =
          SettingsChecks.environmentOf(
              HttpClientTestData.DEFAULTS_RETRY + ".max-retries=5",
              TightResilienceSettings.PAYMENTS_SETTINGS
                  + ".circuit-breaker.failure-rate-threshold=80");

      var properties =
          StarterTestSupport.withCustomizers(
              folder,
              HttpClientPropertiesCustomizer.class,
              List.of(TightResilienceSettings.class),
              () -> HttpClientProperties.resolve(environment));

      var payments = properties.clientSettings(TightResilienceSettings.PAYMENTS);
      assertThat(payments.retry()).isEqualTo(TightResilienceSettings.PAYMENTS_RETRY);
      assertThat(payments.circuitBreaker()).isEqualTo(TightResilienceSettings.PAYMENTS_BREAKER);
      assertThat(properties.clientSettings(HttpClientTestData.INVENTORY).retry().maxRetries())
          .isEqualTo(TightResilienceSettings.SHARED_MAX_RETRIES);
    }
  }

  @Nested
  @DisplayName("a value that breaks its rule stops startup with SX-CORE-1003, naming the rule")
  class InvalidValues {

    /** Each broken rule, with settings under defaults or a client that break it. */
    private static final List<Arguments> BROKEN_RULES =
        List.of(
            argumentSet(
                "max-retries below 0",
                "max-retries-in-range",
                List.of(HttpClientTestData.INVENTORY_RETRY + ".max-retries=-1")),
            argumentSet(
                "max-retries above 9",
                "max-retries-in-range",
                List.of(HttpClientTestData.DEFAULTS_RETRY + ".max-retries=10")),
            argumentSet(
                "multiplier below 1.0",
                "multiplier-in-range",
                List.of(HttpClientTestData.DEFAULTS_RETRY + ".multiplier=0.5")),
            argumentSet(
                "multiplier above 10.0",
                "multiplier-in-range",
                List.of(HttpClientTestData.INVENTORY_RETRY + ".multiplier=11")),
            argumentSet(
                "delay of 0",
                DURATIONS_POSITIVE,
                List.of(HttpClientTestData.INVENTORY_RETRY + ".delay=0s")),
            argumentSet(
                "max-delay of 0, on a fixed client that ignores it otherwise",
                DURATIONS_POSITIVE,
                List.of(
                    HttpClientTestData.INVENTORY_RETRY + ".backoff-policy=fixed",
                    HttpClientTestData.INVENTORY_RETRY + ".max-delay=0s")),
            argumentSet(
                "wait-duration-in-open-state of 0",
                DURATIONS_POSITIVE,
                List.of(HttpClientTestData.INVENTORY_BREAKER + ".wait-duration-in-open-state=0s")),
            argumentSet(
                "negative slow-call-duration-threshold",
                DURATIONS_POSITIVE,
                List.of(HttpClientTestData.DEFAULTS_BREAKER + ".slow-call-duration-threshold=-1s")),
            argumentSet(
                "max-delay below delay in one client",
                "max-delay-at-least-delay",
                List.of(
                    HttpClientTestData.INVENTORY_RETRY + ".delay=2s",
                    HttpClientTestData.INVENTORY_RETRY + MAX_DELAY_OF_1S)),
            argumentSet(
                "a shared max-delay below a client's delay",
                "max-delay-at-least-delay",
                List.of(
                    HttpClientTestData.DEFAULTS_RETRY + MAX_DELAY_OF_1S,
                    HttpClientTestData.INVENTORY_RETRY + ".delay=2s")),
            argumentSet(
                "failure-rate-threshold of 0",
                "failure-rate-threshold-in-range",
                List.of(HttpClientTestData.DEFAULTS_BREAKER + ".failure-rate-threshold=0")),
            argumentSet(
                "failure-rate-threshold above 100",
                "failure-rate-threshold-in-range",
                List.of(HttpClientTestData.INVENTORY_BREAKER + ".failure-rate-threshold=101")),
            argumentSet(
                "slow-call-rate-threshold above 100",
                "slow-call-rate-threshold-in-range",
                List.of(
                    HttpClientTestData.INVENTORY_BREAKER + SLOW_CALL_OF_2S,
                    HttpClientTestData.INVENTORY_BREAKER + ".slow-call-rate-threshold=101")),
            argumentSet(
                "sliding-window-size of 0",
                "sliding-window-size-in-range",
                List.of(HttpClientTestData.INVENTORY_BREAKER + ".sliding-window-size=0")),
            argumentSet(
                "sliding-window-size above 10000",
                "sliding-window-size-in-range",
                List.of(HttpClientTestData.DEFAULTS_BREAKER + ".sliding-window-size=10001")),
            argumentSet(
                "minimum-number-of-calls of 0",
                "minimum-number-of-calls-in-range",
                List.of(HttpClientTestData.DEFAULTS_BREAKER + ".minimum-number-of-calls=0")),
            argumentSet(
                "minimum-number-of-calls above 10000",
                "minimum-number-of-calls-in-range",
                List.of(HttpClientTestData.INVENTORY_BREAKER + ".minimum-number-of-calls=10001")),
            argumentSet(
                "no trial calls",
                "permitted-number-of-calls-in-half-open-state-in-range",
                List.of(
                    HttpClientTestData.INVENTORY_BREAKER
                        + ".permitted-number-of-calls-in-half-open-state=0")),
            argumentSet(
                "more than 1000 trial calls",
                "permitted-number-of-calls-in-half-open-state-in-range",
                List.of(
                    HttpClientTestData.DEFAULTS_BREAKER
                        + ".permitted-number-of-calls-in-half-open-state=1001")),
            argumentSet(
                "a slow-call rate without its duration",
                "slow-call-rate-with-duration",
                List.of(HttpClientTestData.INVENTORY_BREAKER + SLOW_CALL_RATE_OF_50)),
            argumentSet(
                "a shared slow-call rate, with the duration only in one client",
                "slow-call-rate-with-duration",
                List.of(
                    HttpClientTestData.DEFAULTS_BREAKER + SLOW_CALL_RATE_OF_50,
                    HttpClientTestData.INVENTORY_BREAKER + SLOW_CALL_OF_2S)));

    @ParameterizedTest
    @FieldSource("BROKEN_RULES")
    @DisplayName("each broken rule is named, never its value")
    void brokenRuleNamed(String rule, List<String> settings) {
      SettingsChecks.assertSettingsInvalid(rule, settings.toArray(String[]::new));
    }
  }

  @Nested
  @DisplayName("a value at the edge of its rule, or one a policy ignores, starts")
  class AllowedValues {

    /** Settings at each range's edges, and ones that fixed ignores. */
    private static final List<Arguments> ALLOWED_SETTINGS =
        List.of(
            argumentSet(
                "the lowest values",
                List.of(
                    HttpClientTestData.DEFAULTS_RETRY + ".max-retries=0",
                    HttpClientTestData.DEFAULTS_RETRY + ".multiplier=1.0",
                    HttpClientTestData.DEFAULTS_BREAKER + ".failure-rate-threshold=1",
                    HttpClientTestData.DEFAULTS_BREAKER + ".sliding-window-size=1",
                    HttpClientTestData.DEFAULTS_BREAKER + ".minimum-number-of-calls=1",
                    HttpClientTestData.DEFAULTS_BREAKER
                        + ".permitted-number-of-calls-in-half-open-state=1",
                    HttpClientTestData.DEFAULTS_BREAKER + ".slow-call-duration-threshold=1ms",
                    HttpClientTestData.DEFAULTS_BREAKER + ".slow-call-rate-threshold=1")),
            argumentSet(
                "the highest values",
                List.of(
                    HttpClientTestData.INVENTORY_RETRY + ".max-retries=9",
                    HttpClientTestData.INVENTORY_RETRY + ".multiplier=10.0",
                    HttpClientTestData.INVENTORY_BREAKER + ".failure-rate-threshold=100",
                    HttpClientTestData.INVENTORY_BREAKER + ".sliding-window-size=10000",
                    HttpClientTestData.INVENTORY_BREAKER + ".minimum-number-of-calls=10000",
                    HttpClientTestData.INVENTORY_BREAKER
                        + ".permitted-number-of-calls-in-half-open-state=1000",
                    HttpClientTestData.INVENTORY_BREAKER + SLOW_CALL_OF_2S,
                    HttpClientTestData.INVENTORY_BREAKER + ".slow-call-rate-threshold=100")),
            argumentSet(
                "a shared fixed policy with nothing else, and Stratum's multiplier ignored",
                List.of(HttpClientTestData.DEFAULTS_RETRY + ".backoff-policy=fixed")),
            argumentSet(
                "a shared max-delay below the delay of a fixed client, which ignores it",
                List.of(
                    HttpClientTestData.DEFAULTS_RETRY + MAX_DELAY_OF_1S,
                    HttpClientTestData.INVENTORY_RETRY + ".backoff-policy=fixed",
                    HttpClientTestData.INVENTORY_RETRY + ".delay=2s")),
            argumentSet(
                "a client's slow-call rate with the shared duration",
                List.of(
                    HttpClientTestData.DEFAULTS_BREAKER + SLOW_CALL_OF_2S,
                    HttpClientTestData.INVENTORY_BREAKER + SLOW_CALL_RATE_OF_50)),
            argumentSet(
                "minimum-number-of-calls above sliding-window-size",
                List.of(
                    HttpClientTestData.INVENTORY_BREAKER + ".sliding-window-size=10",
                    HttpClientTestData.INVENTORY_BREAKER + ".minimum-number-of-calls=100")));

    @ParameterizedTest
    @FieldSource("ALLOWED_SETTINGS")
    @DisplayName("the settings are used as they are")
    void allowed(List<String> settings) {
      var environment = SettingsChecks.environmentOf(settings.toArray(String[]::new));

      assertThatNoException().isThrownBy(() -> HttpClientProperties.resolve(environment));
    }
  }
}
