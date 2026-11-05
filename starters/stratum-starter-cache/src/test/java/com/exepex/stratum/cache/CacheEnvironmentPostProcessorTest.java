package com.exepex.stratum.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.exepex.stratum.core.error.StratumException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.mock.env.MockEnvironment;

@DisplayName(
    "CacheEnvironmentPostProcessor gives Spring Boot's Redis cache settings Stratum's defaults")
class CacheEnvironmentPostProcessorTest {

  // The keys are spelled out here, so a misspelt key in the module fails the tests.
  private static final String APPLICATION_NAME = "spring.application.name";

  private static final String TIME_TO_LIVE = "spring.cache.redis.time-to-live";

  private static final String KEY_PREFIX = "spring.cache.redis.key-prefix";

  private static final String USE_KEY_PREFIX = "spring.cache.redis.use-key-prefix";

  private static final String ENABLE_STATISTICS = "spring.cache.redis.enable-statistics";

  private static final String CACHE_NULL_VALUES = "spring.cache.redis.cache-null-values";

  private static final String REDIS_TIMEOUT = "spring.data.redis.timeout";

  private static final String REDIS_CONNECT_TIMEOUT = "spring.data.redis.connect-timeout";

  private static final String SERVICE_NAME = "orders";

  /** The value that switches a setting off. */
  private static final String OFF = "false";

  /** Every default, with Stratum's value, for a service named {@value #SERVICE_NAME}. */
  private static final Map<String, String> DEFAULTS =
      Map.of(
          TIME_TO_LIVE, "10m",
          KEY_PREFIX, "orders:",
          ENABLE_STATISTICS, "true",
          CACHE_NULL_VALUES, OFF,
          REDIS_TIMEOUT, "1s",
          REDIS_CONNECT_TIMEOUT, "2s");

  private final CacheEnvironmentPostProcessor postProcessor = new CacheEnvironmentPostProcessor();

  /** An application with Spring Data Redis on its class path, as in these tests. */
  private final SpringApplication applicationWithRedis = new SpringApplication();

  /** An application without Spring Data Redis on its class path. */
  private final SpringApplication applicationWithoutRedis =
      new SpringApplication(
          new DefaultResourceLoader(new FilteredClassLoader(RedisConnectionFactory.class)));

  /** Returns an environment of a service with Stratum's test name. */
  private MockEnvironment namedService() {
    return new MockEnvironment().withProperty(APPLICATION_NAME, SERVICE_NAME);
  }

  /** Runs the post-processor for an application with Spring Data Redis and returns the result. */
  private MockEnvironment runPostProcessor(MockEnvironment environment) {
    postProcessor.postProcessEnvironment(environment, applicationWithRedis);
    return environment;
  }

  /** Checks that each key has its value in the environment. */
  private void assertSet(MockEnvironment environment, Map<String, String> expected) {
    expected.forEach(
        (key, value) -> assertThat(environment.getProperty(key)).as(key).isEqualTo(value));
  }

  @Nested
  @DisplayName("in Spring Boot's startup")
  class InStartup {

    @Test
    @DisplayName("it runs right after the configuration files are read, so it sees their values")
    void runsAfterConfigurationFiles() {
      assertThat(postProcessor.getOrder()).isEqualTo(ConfigDataEnvironmentPostProcessor.ORDER + 1);
    }
  }

  @Nested
  @DisplayName("with the module off")
  class WithModuleOff {

    @Test
    @DisplayName("it sets nothing and needs no service name")
    void setsNothing() {
      var environment =
          runPostProcessor(new MockEnvironment().withProperty(CacheConstants.ENABLED, OFF));

      DEFAULTS.keySet().forEach(key -> assertThat(environment.getProperty(key)).as(key).isNull());
    }
  }

  @Nested
  @DisplayName("with the module on, its default")
  class WithModuleOn {

    /** Every key the module gives a default, with a value a service sets instead. */
    private static final List<Arguments> SERVICE_VALUES =
        List.of(
            arguments(TIME_TO_LIVE, "1h"),
            arguments(KEY_PREFIX, "team:"),
            arguments(ENABLE_STATISTICS, OFF),
            arguments(CACHE_NULL_VALUES, "true"),
            arguments(REDIS_TIMEOUT, "5s"),
            arguments(REDIS_CONNECT_TIMEOUT, "10s"));

    /** The two files, one in each format, that set the time to live to one hour. */
    private static final List<Arguments> SERVICE_FILES =
        List.of(
            arguments("cache-properties.properties", new PropertiesPropertySourceLoader()),
            arguments("cache-yaml.yml", new YamlPropertySourceLoader()));

    @Test
    @DisplayName("it sets every default, the key prefix from the service's name")
    void setsEveryDefault() {
      assertSet(runPostProcessor(namedService()), DEFAULTS);
    }

    @ParameterizedTest(name = "{0}={1}")
    @FieldSource("SERVICE_VALUES")
    @DisplayName("the service's own value wins")
    void keepsServiceValue(String key, String serviceValue) {
      var environment = runPostProcessor(namedService().withProperty(key, serviceValue));

      assertThat(environment.getProperty(key)).isEqualTo(serviceValue);
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("SERVICE_FILES")
    @DisplayName("the service's value from application.properties or application.yml wins")
    void keepsServiceFileValue(String file, PropertySourceLoader loader) throws IOException {
      var environment = namedService();
      loader
          .load(file, new ClassPathResource(file))
          .forEach(environment.getPropertySources()::addFirst);

      runPostProcessor(environment);

      assertThat(environment.getProperty(TIME_TO_LIVE)).isEqualTo("1h");
      assertThat(environment.getProperty(KEY_PREFIX)).isEqualTo("orders:");
    }

    @Test
    @DisplayName("a value the service sets in Java as a default property wins")
    void keepsServiceDefaultProperty() {
      var environment = namedService();
      DefaultPropertiesPropertySource.addOrMerge(
          Map.of(TIME_TO_LIVE, "1h"), environment.getPropertySources());

      runPostProcessor(environment);

      assertThat(environment.getProperty(TIME_TO_LIVE)).isEqualTo("1h");
    }
  }

  @Nested
  @DisplayName("with no service name")
  class WithoutServiceName {

    /** A service's own prefix, and a service that turns prefixes off. */
    private static final List<Arguments> PREFIX_CHOICES =
        List.of(arguments(KEY_PREFIX, "team:"), arguments(USE_KEY_PREFIX, OFF));

    @Test
    @DisplayName("with Spring Data Redis present, startup stops with SX-CACHE-1001")
    void stopsWithoutName() {
      var environment = new MockEnvironment();

      assertThatThrownBy(() -> runPostProcessor(environment))
          .isInstanceOfSatisfying(
              StratumException.class,
              failure ->
                  assertThat(failure.getErrorCode())
                      .isEqualTo(CacheErrorCode.APPLICATION_NAME_MISSING));
    }

    @Test
    @DisplayName("with a blank name, startup stops too")
    void stopsWithBlankName() {
      var environment = new MockEnvironment().withProperty(APPLICATION_NAME, " ");

      assertThatThrownBy(() -> runPostProcessor(environment)).isInstanceOf(StratumException.class);
    }

    @ParameterizedTest(name = "{0}={1}")
    @FieldSource("PREFIX_CHOICES")
    @DisplayName("a service that chose its own prefix, or none, starts")
    void startsWithOwnPrefixChoice(String key, String value) {
      var environment = new MockEnvironment().withProperty(key, value);

      assertThatCode(() -> runPostProcessor(environment)).doesNotThrowAnyException();
      assertThat(environment.getProperty(TIME_TO_LIVE)).isEqualTo("10m");
    }

    @Test
    @DisplayName("without Spring Data Redis, the service starts with the defaults")
    void startsWithoutRedis() {
      var environment = new MockEnvironment();

      assertThatCode(
              () -> postProcessor.postProcessEnvironment(environment, applicationWithoutRedis))
          .doesNotThrowAnyException();
      assertThat(environment.getProperty(TIME_TO_LIVE)).isEqualTo("10m");
    }
  }
}
