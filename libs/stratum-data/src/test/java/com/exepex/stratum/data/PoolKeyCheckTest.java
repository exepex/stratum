package com.exepex.stratum.data;

import static com.exepex.stratum.data.testsupport.NamedPoolTestData.BATCH;
import static com.exepex.stratum.data.testsupport.NamedPoolTestData.BATCH_KEYS;
import static com.exepex.stratum.data.testsupport.NamedPoolTestData.withVariables;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.exepex.stratum.core.error.StratumException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("PoolKeyCheck stops startup for a named pool's bad name or key, in every spelling")
class PoolKeyCheckTest {

  /** The first part of every pool variable. */
  private static final String VARIABLES = "STRATUM_DATA_SOURCES_BATCH_";

  /** Names no pool may have: a dash, upper case, a digit first, too long, empty, main. */
  private static final List<String> BAD_NAMES =
      List.of("reporting-db", "Batch", "1st", "a".repeat(32), "main", "rep_db");

  /** The variables Spring Boot binds to a pool's keys, each of which the check must accept. */
  private static final List<String> BOUND_VARIABLES =
      List.of(
          VARIABLES + "QUERY_TIMEOUT",
          VARIABLES + "QUERYTIMEOUT",
          VARIABLES + "FETCH_SIZE",
          VARIABLES + "HIKARI_MAXIMUM_POOL_SIZE",
          VARIABLES + "HIKARI_MAXIMUMPOOLSIZE",
          VARIABLES + "HIKARI_DATA_SOURCE_PROPERTIES_SSLMODE");

  /** The seconds of the time limit a variable sets. */
  private static final long FIVE = 5;

  private final PoolKeyCheck check = new PoolKeyCheck();

  /** Checks the environment with its own settings, as the registrar does. */
  private void checkAll(Environment environment) {
    check.checkKeys(environment);
    check.checkSettings(DataProperties.resolve(environment));
  }

  /** Returns the failure of a check that must fail with {@code SX-DATA-1004}. */
  private StratumException failureOf(Environment environment) {
    var failure = catchThrowableOfType(StratumException.class, () -> checkAll(environment));
    assertThat(failure.getErrorCode()).isEqualTo(DataErrorCode.POOL_SETTING_INVALID);
    return failure;
  }

  @Nested
  @DisplayName("from properties files")
  class Files {

    @Test
    @DisplayName("every key of a pool, HikariCP's and a driver property included, passes")
    void acceptsKnownKeys() {
      var environment =
          new MockEnvironment()
              .withProperty(BATCH_KEYS + "url", "jdbc:postgresql://db/orders")
              .withProperty(BATCH_KEYS + "username", "app")
              .withProperty(BATCH_KEYS + "query-timeout", "5s")
              .withProperty(BATCH_KEYS + "transaction-timeout", "10m")
              .withProperty(BATCH_KEYS + "fetch-size", "500")
              .withProperty(BATCH_KEYS + "hikari.maximum-pool-size", "4")
              .withProperty(BATCH_KEYS + "hikari.data-source-properties.sslmode", "verify-full");

      assertThatCode(() -> checkAll(environment)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a misspelt key fails and is named, so it is never silently dropped")
    void rejectsUnknownKey() {
      var environment = new MockEnvironment().withProperty(BATCH_KEYS + "querytimeot", "5s");

      assertThat(failureOf(environment)).hasMessageContaining(BATCH_KEYS + "querytimeot");
    }

    @Test
    @DisplayName("a HikariCP key HikariCP does not have fails")
    void rejectsUnknownHikariKey() {
      var environment = new MockEnvironment().withProperty(BATCH_KEYS + "hikari.maximum-size", "4");

      assertThat(failureOf(environment)).hasMessageContaining("hikari.maximum-size");
    }

    @Test
    @DisplayName("a value on the pool's name itself, with no key, fails")
    void rejectsBareName() {
      var environment = new MockEnvironment().withProperty("stratum.data.sources.batch", "x");

      assertThat(failureOf(environment)).hasMessageContaining("stratum.data.sources.batch");
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("com.exepex.stratum.data.PoolKeyCheckTest#BAD_NAMES")
    @DisplayName("a name that breaks the rule fails")
    void rejectsBadName(String name) {
      var environment =
          new MockEnvironment().withProperty("stratum.data.sources." + name + ".url", "u");

      assertThat(failureOf(environment))
          .hasMessageContaining("stratum.data.sources." + name + " is not lower-case letters");
    }

    @Test
    @DisplayName("a name with a line break is logged on one line")
    void cleansName() {
      assertThat(catchThrowableOfType(StratumException.class, () -> check.checkName("a\nb")))
          .hasMessageNotContaining("\n");
    }
  }

  @Nested
  @DisplayName("from environment variables")
  class Variables {

    @ParameterizedTest(name = "{0}")
    @FieldSource("com.exepex.stratum.data.PoolKeyCheckTest#BOUND_VARIABLES")
    @DisplayName("each spelling Spring Boot binds passes")
    void acceptsBoundVariables(String variable) {
      assertThatCode(() -> checkAll(withVariables(Map.of(variable, "5"))))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a misspelt HikariCP variable fails, in the pool's settings")
    void rejectsMisspeltHikariVariable() {
      var environment = withVariables(Map.of(VARIABLES + "HIKARI_MAXIMUM_SIZE", "4"));

      assertThat(failureOf(environment)).hasMessageContaining("hikari.maximum.size");
    }

    @Test
    @DisplayName("QUERY_TIMEOUT binds to the pool's query-timeout")
    void bindsQueryTimeout() {
      var environment = withVariables(Map.of(VARIABLES + "QUERY_TIMEOUT", "5s"));

      assertThat(DataProperties.resolve(environment).sources().get(BATCH).queryTimeout())
          .isEqualTo(Duration.ofSeconds(FIVE));
    }

    @Test
    @DisplayName("REPORTING_DB_URL, read as the key db-url of the pool reporting, fails")
    void rejectsSplitName() {
      var environment =
          withVariables(Map.of("STRATUM_DATA_SOURCES_REPORTING_DB_URL", "jdbc:postgresql://db/r"));

      assertThat(failureOf(environment)).hasMessageContaining("stratum.data.sources.reporting.db");
    }
  }

  @Nested
  @DisplayName("in Java settings")
  class JavaSettings {

    @Test
    @DisplayName("a pool a customizer adds with a bad name fails")
    void rejectsBadName() {
      var settings =
          DataProperties.builder()
              .sources(Map.of("Reports", PoolSettings.builder().url("u").build()))
              .build();

      var failure =
          catchThrowableOfType(StratumException.class, () -> check.checkSettings(settings));

      assertThat(failure.getErrorCode()).isEqualTo(DataErrorCode.POOL_SETTING_INVALID);
    }

    @Test
    @DisplayName("a HikariCP key a customizer sets that HikariCP does not have fails")
    void rejectsUnknownHikariKey() {
      var settings =
          DataProperties.builder()
              .sources(
                  Map.of(BATCH, PoolSettings.builder().hikari(Map.of("maximum-size", "4")).build()))
              .build();

      var failure =
          catchThrowableOfType(StratumException.class, () -> check.checkSettings(settings));

      assertThat(failure).hasMessageContaining("stratum.data.sources.batch.hikari.maximum-size");
    }

    @Test
    @DisplayName("HikariCP keys a customizer sets, in any spelling, pass")
    void acceptsHikariKeys() {
      var settings =
          DataProperties.builder()
              .sources(
                  Map.of(
                      BATCH,
                      PoolSettings.builder()
                          .hikari(
                              Map.of(
                                  "maximum-pool-size", "4",
                                  "minimumIdle", "1",
                                  "data-source-properties.sslmode", "verify-full"))
                          .build()))
              .build();

      assertThatCode(() -> check.checkSettings(settings)).doesNotThrowAnyException();
    }
  }
}
