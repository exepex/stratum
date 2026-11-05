package com.exepex.stratum.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
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

  @Nested
  @DisplayName("bound from the environment")
  class Binding {

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
            arguments("caches.reports.max-key-size", "-1B", SIZES_POSITIVE));

    @Test
    @DisplayName(
        "unset, the cap is 100 caches, warnings repeat at most once a minute, and every cache"
            + " stores keys up to 1 KiB and values up to 1 MiB")
    void defaultsAreSafe() {
      var properties = StratumCacheProperties.resolve(new MockEnvironment());

      assertThat(properties.maxCaches()).isEqualTo(DEFAULT_MAX_CACHES);
      assertThat(properties.warningInterval()).isEqualTo(DEFAULT_WARNING_INTERVAL);
      assertThat(properties.caches()).isEmpty();
      assertThat(properties.maxKeyBytesFor(REPORTS)).isEqualTo(DEFAULT_KEY_BYTES);
      assertThat(properties.maxValueBytesFor(REPORTS)).isEqualTo(DEFAULT_VALUE_BYTES);
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
    }
  }
}
