package com.exepex.stratum.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.exepex.stratum.cache.testsupport.ThreeCachesSettings;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.test.StarterTestSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("The cache settings have safe defaults, are checked at startup and can be set in Java")
class StratumCachePropertiesTest {

  /** The default cap on the number of caches. */
  private static final int DEFAULT_MAX_CACHES = 100;

  /** The value both test files give each setting. */
  private static final int MAX_CACHES_IN_FILES = 7;

  /** The default interval of one cache's warnings. */
  private static final Duration DEFAULT_WARNING_INTERVAL = Duration.ofMinutes(1);

  /** The interval both test files set. */
  private static final Duration WARNING_INTERVAL_IN_FILES = Duration.ofMinutes(5);

  /** The cache both test files give its own value limit. */
  private static final String REPORTS = "reports";

  /** The default key limit, 1 KiB. */
  private static final long DEFAULT_KEY_BYTES = 1024;

  /** The default value limit, 1 MiB. */
  private static final long DEFAULT_VALUE_BYTES = 1024 * 1024;

  /** The value limit both test files set for every cache, 2 MiB. */
  private static final long VALUE_BYTES_IN_FILES = 2 * DEFAULT_VALUE_BYTES;

  /** The value limit both test files set for {@value #REPORTS}, 5 MiB. */
  private static final long REPORTS_VALUE_BYTES_IN_FILES = 5 * DEFAULT_VALUE_BYTES;

  /** The time to live both test files set for {@value #REPORTS}. */
  private static final Duration REPORTS_TIME_TO_LIVE_IN_FILES = Duration.ofHours(1);

  /** The command timeout both test files set for {@value #REPORTS}. */
  private static final Duration REPORTS_COMMAND_TIMEOUT_IN_FILES = Duration.ofSeconds(3);

  /** The most entries both test files give the reports cache. */
  private static final long REPORTS_MAX_ENTRIES_IN_FILES = 500;

  /** The default time Redis caches skip Redis after a failure. */
  private static final Duration DEFAULT_REDIS_RETRY_INTERVAL = Duration.ofSeconds(5);

  /** The default number of failures in a row that make Redis caches skip Redis. */
  private static final int DEFAULT_REDIS_FAILURE_THRESHOLD = 3;

  /** The failure threshold both test files set. */
  private static final int REDIS_FAILURE_THRESHOLD_IN_FILES = 5;

  /** The retry interval both test files set. */
  private static final Duration REDIS_RETRY_INTERVAL_IN_FILES = Duration.ofSeconds(2);

  /** The packages both test files allow. */
  private static final List<String> ALLOWED_PACKAGES_IN_FILES =
      List.of("com.acme.shared", "com.acme.billing");

  @Nested
  @DisplayName("bound from the environment")
  class Binding {

    /** The rule a time to live or command timeout of 0 or below breaks. */
    private static final String DURATIONS_POSITIVE = "durations-positive breaks @AssertTrue";

    /** The rule a size limit of 0 or below breaks. */
    private static final String SIZES_POSITIVE = "sizes-positive breaks @AssertTrue";

    /** The two files, one in each format, with the same settings. */
    private static final List<Arguments> SERVICE_FILES =
        List.of(
            arguments("cache-settings.properties", new PropertiesPropertySourceLoader()),
            arguments("cache-settings.yml", new YamlPropertySourceLoader()));

    /**
     * Values outside their range, each with its key and the rule the startup error names. The error
     * names the key and the rule, never the value.
     */
    private static final List<Arguments> OUT_OF_RANGE =
        List.of(
            arguments("max-caches", "0", "max-caches breaks @Min(value=1)"),
            arguments("warning-interval", "500ms", "warning-interval-in-range breaks @AssertTrue"),
            arguments("warning-interval", "2h", "warning-interval-in-range breaks @AssertTrue"),
            arguments("max-key-size", "0B", SIZES_POSITIVE),
            arguments("max-value-size", "0B", SIZES_POSITIVE),
            arguments("caches.reports.max-value-size", "0B", SIZES_POSITIVE),
            arguments("caches.reports.max-key-size", "-1B", SIZES_POSITIVE),
            arguments("caches.reports.time-to-live", "0s", DURATIONS_POSITIVE),
            arguments("caches.reports.command-timeout", "0ms", DURATIONS_POSITIVE),
            arguments("caches.reports.command-timeout", "-1s", DURATIONS_POSITIVE),
            arguments("caches.reports.max-entries", "0", SIZES_POSITIVE),
            arguments("caches.reports.max-entries", "-1", SIZES_POSITIVE),
            arguments(
                "redis-retry-interval",
                "999ms",
                "redis-retry-interval-in-range breaks @AssertTrue"),
            arguments(
                "redis-retry-interval", "6m", "redis-retry-interval-in-range breaks @AssertTrue"),
            arguments(
                "redis-failure-threshold", "0", "redis-failure-threshold breaks @Min(value=1)"),
            arguments(
                "redis-failure-threshold", "-1", "redis-failure-threshold breaks @Min(value=1)"),
            arguments(
                "redis-failure-threshold", "11", "redis-failure-threshold breaks @Max(value=10)"),
            arguments(
                "serialization.allowed-packages",
                " ",
                "serialization.allowed-packages[*] breaks @NotBlank"));

    @Test
    @DisplayName(
        "unset, the cap is 100 caches, warnings repeat at most once a minute, every cache stores"
            + " keys up to 1 KiB and values up to 1 MiB, Redis is skipped after 3 failures in a row"
            + " and retried after 5 seconds, and no package is allowed beyond the service's own;"
            + " Caffeine caches store no nulls")
    void defaultsAreSafe() {
      var properties = StratumCacheProperties.resolve(new MockEnvironment());

      assertThat(properties.maxCaches()).isEqualTo(DEFAULT_MAX_CACHES);
      assertThat(properties.warningInterval()).isEqualTo(DEFAULT_WARNING_INTERVAL);
      assertThat(properties.caches()).isEmpty();
      assertThat(properties.maxKeyBytesFor(REPORTS)).isEqualTo(DEFAULT_KEY_BYTES);
      assertThat(properties.maxValueBytesFor(REPORTS)).isEqualTo(DEFAULT_VALUE_BYTES);
      assertThat(properties.redisRetryInterval()).isEqualTo(DEFAULT_REDIS_RETRY_INTERVAL);
      assertThat(properties.redisFailureThreshold()).isEqualTo(DEFAULT_REDIS_FAILURE_THRESHOLD);
      assertThat(properties.serialization().allowedPackages()).isEmpty();
      assertThat(properties.timesToLive()).isEmpty();
      assertThat(properties.commandTimeouts()).isEmpty();
      assertThat(properties.maxEntries()).isEmpty();
      assertThat(properties.caffeine().cacheNullValues()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("SERVICE_FILES")
    @DisplayName("the service's values from application.properties or application.yml win")
    void filesWin(String file, PropertySourceLoader loader) throws IOException {
      var environment = new MockEnvironment();
      loader
          .load(file, new ClassPathResource(file))
          .forEach(environment.getPropertySources()::addFirst);

      var properties = StratumCacheProperties.resolve(environment);

      assertThat(properties.maxCaches()).isEqualTo(MAX_CACHES_IN_FILES);
      assertThat(properties.warningInterval()).isEqualTo(WARNING_INTERVAL_IN_FILES);
      assertThat(properties.maxValueBytesFor(REPORTS)).isEqualTo(REPORTS_VALUE_BYTES_IN_FILES);
      assertThat(properties.maxValueBytesFor("products")).isEqualTo(VALUE_BYTES_IN_FILES);
      assertThat(properties.maxKeyBytesFor(REPORTS)).isEqualTo(DEFAULT_KEY_BYTES);
      assertThat(properties.redisRetryInterval()).isEqualTo(REDIS_RETRY_INTERVAL_IN_FILES);
      assertThat(properties.redisFailureThreshold()).isEqualTo(REDIS_FAILURE_THRESHOLD_IN_FILES);
      assertThat(properties.serialization().allowedPackages())
          .containsExactlyElementsOf(ALLOWED_PACKAGES_IN_FILES);
      assertThat(properties.timesToLive())
          .containsExactly(entry(REPORTS, REPORTS_TIME_TO_LIVE_IN_FILES));
      assertThat(properties.commandTimeouts())
          .containsExactly(entry(REPORTS, REPORTS_COMMAND_TIMEOUT_IN_FILES));
      assertThat(properties.maxEntries())
          .containsExactly(entry(REPORTS, REPORTS_MAX_ENTRIES_IN_FILES));
      assertThat(properties.caffeine().cacheNullValues()).isTrue();
    }

    @ParameterizedTest(name = "{0}={1}")
    @FieldSource("OUT_OF_RANGE")
    @DisplayName(
        "a value out of range stops startup with SX-CORE-1003, naming the key and the rule")
    void outOfRangeIsRejected(String key, String value, String rule) {
      var outOfRange = new MockEnvironment().withProperty(CacheConstants.PREFIX + "." + key, value);

      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(() -> StratumCacheProperties.resolve(outOfRange))
          .withMessage("SX-CORE-1003: Invalid settings: " + CacheConstants.PREFIX + "." + rule);
    }
  }

  @Nested
  @DisplayName("changed in Java")
  class Customized {

    @Test
    @DisplayName("a customizer listed in spring.factories wins over the files")
    void customizerWins(@TempDir Path folder) throws IOException {
      var files = new MockEnvironment().withProperty(CacheConstants.MAX_CACHES, "7");

      var properties =
          StarterTestSupport.withCustomizers(
              folder,
              StratumCachePropertiesCustomizer.class,
              List.of(ThreeCachesSettings.class),
              () -> StratumCacheProperties.resolve(files));

      assertThat(properties.maxCaches()).isEqualTo(ThreeCachesSettings.MAX_CACHES);
      assertThat(properties.redisRetryInterval())
          .isEqualTo(ThreeCachesSettings.REDIS_RETRY_INTERVAL);
      assertThat(properties.redisFailureThreshold())
          .isEqualTo(ThreeCachesSettings.REDIS_FAILURE_THRESHOLD);
      assertThat(properties.serialization().allowedPackages())
          .containsExactly(ThreeCachesSettings.ALLOWED_PACKAGE);
      assertThat(properties.maxEntries())
          .containsExactly(entry(REPORTS, ThreeCachesSettings.REPORTS_MAX_ENTRIES));
      assertThat(properties.caffeine().cacheNullValues()).isTrue();
    }
  }
}
