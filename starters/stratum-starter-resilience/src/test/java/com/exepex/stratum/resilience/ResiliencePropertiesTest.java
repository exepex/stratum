package com.exepex.stratum.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.resilience.testsupport.OrdersResilienceSettings;
import com.exepex.stratum.resilience.testsupport.ResilienceTestData;
import com.exepex.stratum.test.MetadataDefaultChecks;
import com.exepex.stratum.test.StarterTestSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("ResilienceProperties binds stratum.resilience.* and checks each value")
class ResiliencePropertiesTest {

  /** The limit the test files give the reports route. */
  private static final int FILES_REPORTS_LIMIT = 10;

  /** A wait between whole seconds, which the header rounds up. */
  private static final Duration WAIT_BETWEEN_SECONDS = Duration.ofMillis(1500);

  /** Returns the settings resolved from properties set on a fresh environment. */
  private ResilienceProperties resolve(Map<String, String> properties) {
    var environment = new MockEnvironment();
    properties.forEach(environment::setProperty);
    return ResilienceProperties.resolve(environment);
  }

  @Nested
  @DisplayName("with nothing set")
  class Defaults {

    @Test
    @DisplayName("no route is limited and a refused caller is told to wait 1 second")
    void noRouteAndOneSecond() {
      var incoming = resolve(Map.of()).incoming();

      assertThat(incoming.routes()).isEmpty();
      assertThat(incoming.retryAfter()).isEqualTo(Duration.ofSeconds(1));
      assertThat(incoming.retryAfterSeconds()).isEqualTo("1");
    }

    @Test
    @DisplayName("each default the metadata lists is the value the module uses")
    void metadataListsDefaultsInEffect() throws IOException {
      MetadataDefaultChecks.checkMetadataDefaults(ResiliencePropertiesTest.this::resolve);
    }
  }

  @Nested
  @DisplayName("from a service's file")
  class FromFiles {

    /** The same settings as a .properties and a .yml file. */
    private static final List<String> FILES = List.of("resilience.properties", "resilience.yml");

    @ParameterizedTest
    @FieldSource("FILES")
    @DisplayName("routes and retry-after bind the same from either format")
    void bindsBothFormats(String file) throws IOException {
      var incoming = ResilienceProperties.resolve(environmentOf(file)).incoming();

      assertThat(incoming.routes())
          .containsOnlyKeys(ResilienceTestData.REPORTS_ROUTE, "/exports/{id}");
      assertThat(incoming.routes().get(ResilienceTestData.REPORTS_ROUTE).maxConcurrent())
          .isEqualTo(FILES_REPORTS_LIMIT);
      assertThat(incoming.retryAfter()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName(ResilienceTestData.JAVA_CONFIGURATION_WINS)
    void javaConfigurationWins(@TempDir Path folder) throws IOException {
      var incoming =
          StarterTestSupport.withCustomizers(
                  folder,
                  ResiliencePropertiesCustomizer.class,
                  List.of(OrdersResilienceSettings.class),
                  this::resolveFromPropertiesFile)
              .incoming();

      assertThat(incoming.routes().get(ResilienceTestData.REPORTS_ROUTE).maxConcurrent())
          .isEqualTo(OrdersResilienceSettings.REPORTS_LIMIT);
      assertThat(incoming.retryAfter()).isEqualTo(OrdersResilienceSettings.RETRY_AFTER);
    }

    /** Returns the settings of the .properties file. */
    private ResilienceProperties resolveFromPropertiesFile() {
      try {
        return ResilienceProperties.resolve(environmentOf(FILES.getFirst()));
      } catch (IOException unreadable) {
        throw new IllegalStateException(unreadable);
      }
    }

    /** Returns an environment holding a test file's settings, as a service's file does. */
    private StandardEnvironment environmentOf(String file) throws IOException {
      PropertySourceLoader loader =
          file.endsWith(".yml")
              ? new YamlPropertySourceLoader()
              : new PropertiesPropertySourceLoader();
      var environment = new StandardEnvironment();
      loader
          .load(file, new ClassPathResource(file))
          .forEach(environment.getPropertySources()::addFirst);
      return environment;
    }
  }

  @Nested
  @DisplayName("with a value out of range")
  class BadValues {

    /**
     * Limits a route cannot have: none, as a route built in Java without one has, and one over the
     * largest.
     */
    private static final List<String> BAD_LIMITS = List.of("0", "100001");

    /** Waits a refused caller cannot be told: under 1 second, and over 1 hour. */
    private static final List<String> BAD_WAITS = List.of("500ms", "2h");

    @ParameterizedTest
    @FieldSource("BAD_LIMITS")
    @DisplayName("a route's max-concurrent stops startup, naming the key and the rule")
    void badLimitStopsStartup(String limit) {
      var properties = Map.of(ResilienceTestData.REPORTS_MAX_CONCURRENT, limit);

      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(() -> resolve(properties))
          .withMessageStartingWith("SX-CORE-1003: ")
          .withMessageContaining("max-concurrent");
    }

    @ParameterizedTest
    @FieldSource("BAD_WAITS")
    @DisplayName("a retry-after out of 1s to 1h stops startup with retry-after-in-range")
    void badWaitStopsStartup(String wait) {
      var properties = Map.of(ResilienceConstants.RETRY_AFTER, wait);

      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(() -> resolve(properties))
          .withMessageContaining("retry-after-in-range");
    }
  }

  @Nested
  @DisplayName("the Retry-After value")
  class RetryAfterSeconds {

    @Test
    @DisplayName("is whole seconds, rounded up")
    void roundsUp() {
      var incoming = ResilienceTestData.incoming(Map.of());

      assertThat(incoming.retryAfter(WAIT_BETWEEN_SECONDS).build().retryAfterSeconds())
          .isEqualTo("2");
      assertThat(incoming.retryAfter(Duration.ofHours(1)).build().retryAfterSeconds())
          .isEqualTo("3600");
    }

    @Test
    @DisplayName("a builder that leaves the routes out gives no route")
    void builderWithoutRoutes() {
      assertThat(
              ResilienceProperties.IncomingSettings.builder()
                  .retryAfter(Duration.ofSeconds(1))
                  .build()
                  .routes())
          .isEmpty();
    }
  }
}
