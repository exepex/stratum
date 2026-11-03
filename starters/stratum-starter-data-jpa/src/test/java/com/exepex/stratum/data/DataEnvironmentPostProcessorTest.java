package com.exepex.stratum.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

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

@DisplayName("DataEnvironmentPostProcessor gives Spring Boot's data settings Stratum's defaults")
class DataEnvironmentPostProcessorTest {

  // The keys are spelled out here, so a misspelt key in the module fails the tests.
  private static final String CONNECTION_TIMEOUT = "spring.datasource.hikari.connection-timeout";

  private static final String IN_CLAUSE_PADDING =
      "spring.jpa.properties.hibernate.query.in_clause_parameter_padding";

  private static final String BATCH_SIZE = "spring.jpa.properties.hibernate.jdbc.batch_size";

  private static final String ORDER_INSERTS = "spring.jpa.properties.hibernate.order_inserts";

  private static final String ORDER_UPDATES = "spring.jpa.properties.hibernate.order_updates";

  private static final String LOG_JDBC_ERRORS = "spring.jpa.properties.hibernate.jdbc.log.errors";

  private static final String LOG_JDBC_WARNINGS =
      "spring.jpa.properties.hibernate.jdbc.log.warnings";

  private static final String OPEN_IN_VIEW = "spring.jpa.open-in-view";

  private static final String FAIL_ON_PAGINATION =
      "spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch";

  private static final String TRANSACTION_TIMEOUT = "spring.transaction.default-timeout";

  private static final String BACKWARD_COMPATIBLE = "stratum.backward-compatible";

  private static final String ON = "true";

  private static final String OFF = "false";

  /** The defaults that apply in both modes, with Stratum's values. */
  private static final Map<String, String> INTERNAL_DEFAULTS =
      Map.of(
          CONNECTION_TIMEOUT, "3000",
          IN_CLAUSE_PADDING, ON,
          BATCH_SIZE, "50",
          ORDER_INSERTS, ON,
          ORDER_UPDATES, ON,
          LOG_JDBC_ERRORS, OFF,
          LOG_JDBC_WARNINGS, OFF);

  /** The defaults that apply only with {@code stratum.backward-compatible=false}. */
  private static final Map<String, String> EXTERNAL_DEFAULTS =
      Map.of(OPEN_IN_VIEW, OFF, FAIL_ON_PAGINATION, ON, TRANSACTION_TIMEOUT, "60s");

  /** Every key the module gives a default, with a value a service sets instead. */
  private static final List<Arguments> SERVICE_VALUES =
      List.of(
          arguments(CONNECTION_TIMEOUT, "10000"),
          arguments(IN_CLAUSE_PADDING, OFF),
          arguments(BATCH_SIZE, "20"),
          arguments(ORDER_INSERTS, OFF),
          arguments(ORDER_UPDATES, OFF),
          arguments(OPEN_IN_VIEW, ON),
          arguments(FAIL_ON_PAGINATION, OFF),
          arguments(TRANSACTION_TIMEOUT, "300s"));

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
      assertSet(environment, Map.of(OPEN_IN_VIEW, OFF, FAIL_ON_PAGINATION, ON));
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
}
