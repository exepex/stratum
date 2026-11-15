package com.exepex.stratum.data;

import static com.exepex.stratum.data.testsupport.NamedPoolTestData.BATCH;
import static com.exepex.stratum.data.testsupport.NamedPoolTestData.BATCH_KEYS;
import static com.exepex.stratum.data.testsupport.NamedPoolTestData.URL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.exepex.stratum.core.error.CoreErrorCode;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.data.testsupport.ReportsPoolCustomizer;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("DataProperties binds the named pools and the cap, and rejects values out of range")
class DataPropertiesTest {

  /** Returns the module's settings from the keys. */
  private DataProperties resolve(Map<String, String> keys) {
    var environment = new MockEnvironment();
    keys.forEach(environment::setProperty);
    return DataProperties.resolve(environment);
  }

  @Nested
  @DisplayName("a value out of range stops startup")
  class OutOfRange {

    /** Each key with a value out of range, and the rule the failure names. */
    static final List<Map.Entry<String, String>> BAD_POOL_KEYS =
        List.of(
            Map.entry(BATCH_KEYS + "query-timeout", "-1s"),
            Map.entry(BATCH_KEYS + "transaction-timeout", "-1s"),
            Map.entry(BATCH_KEYS + "fetch-size", "-1"));

    /** Each cap below one. */
    static final List<String> BAD_CAPS = List.of("0", "-5");

    @ParameterizedTest(name = "{0}")
    @FieldSource("BAD_POOL_KEYS")
    @DisplayName("a negative time limit or fetch size of a named pool")
    void negativePoolValue(Map.Entry<String, String> key) {
      var failure =
          catchThrowableOfType(
              StratumException.class,
              () -> resolve(Map.of(BATCH_KEYS + "url", URL, key.getKey(), key.getValue())));

      assertThat(failure.getErrorCode()).isEqualTo(CoreErrorCode.INVALID_SETTINGS);
      assertThat(failure).hasMessageContaining("not-negative");
    }

    @ParameterizedTest(name = "max-connections={0}")
    @FieldSource("BAD_CAPS")
    @DisplayName("a cap on all pools' connections below one")
    void capBelowOne(String cap) {
      var failure =
          catchThrowableOfType(
              StratumException.class, () -> resolve(Map.of("stratum.data.max-connections", cap)));

      assertThat(failure.getErrorCode()).isEqualTo(CoreErrorCode.INVALID_SETTINGS);
      assertThat(failure).hasMessageContaining("max-connections-positive");
    }

    @Test
    @DisplayName("zero is in range for each of a pool's values")
    void zeroIsInRange() {
      var settings =
          resolve(
              Map.of(
                  BATCH_KEYS + "url", URL,
                  BATCH_KEYS + "query-timeout", "0",
                  BATCH_KEYS + "transaction-timeout", "0",
                  BATCH_KEYS + "fetch-size", "0"));

      assertThat(settings.sources().get(BATCH).fetchSize()).isZero();
    }
  }

  @Nested
  @DisplayName("application.yml")
  class FromYaml {

    /** The cap that named-pools.yml sets. */
    static final int YAML_CAP = 25;

    /** The fetch size that named-pools.yml sets on the pool batch. */
    static final int YAML_FETCH_SIZE = 500;

    @Test
    @DisplayName("binds a named pool's keys and the cap")
    void bindsPoolAndCap() throws IOException {
      var environment = new MockEnvironment();
      new YamlPropertySourceLoader()
          .load("named-pools", new ClassPathResource("named-pools.yml"))
          .forEach(environment.getPropertySources()::addFirst);

      var settings = DataProperties.resolve(environment);

      var batch = settings.sources().get(BATCH);
      assertThat(settings.maxConnectionsOrStandard()).isEqualTo(YAML_CAP);
      assertThat(batch.url()).isEqualTo(URL);
      assertThat(batch.fetchSize()).isEqualTo(YAML_FETCH_SIZE);
      assertThat(batch.queryTimeout()).isEqualTo(Duration.ofMinutes(2));
      assertThat(batch.hikari()).containsEntry("maximum-pool-size", "4");
    }
  }

  @Nested
  @DisplayName("a Java customizer")
  class JavaCustomizer {

    @Test
    @DisplayName("wins over the file for a named pool's key")
    void winsOverFile() {
      var settings =
          resolve(
              Map.of(
                  "stratum.data.query-timeout",
                  ReportsPoolCustomizer.MARKER.toMillis() + "ms",
                  "stratum.data.sources.reports.url",
                  "jdbc:postgresql://localhost:1/other"));

      assertThat(settings.sources().get(ReportsPoolCustomizer.REPORTS).url()).isEqualTo(URL);
    }
  }
}
