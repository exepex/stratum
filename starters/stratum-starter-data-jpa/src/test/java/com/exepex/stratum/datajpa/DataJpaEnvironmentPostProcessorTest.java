package com.exepex.stratum.datajpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.exepex.stratum.data.DataConstants;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("DataJpaEnvironmentPostProcessor gives Spring Boot's JPA settings Stratum's defaults")
class DataJpaEnvironmentPostProcessorTest {

  // The keys are spelled out here, so a misspelt key in the module fails the tests.
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

  private static final String BACKWARD_COMPATIBLE = "stratum.backward-compatible";

  private static final String ON = "true";

  private static final String OFF = "false";

  /** The defaults that apply in both modes, with Stratum's values. */
  private static final Map<String, String> INTERNAL_DEFAULTS =
      Map.of(
          IN_CLAUSE_PADDING, ON,
          BATCH_SIZE, "50",
          ORDER_INSERTS, ON,
          ORDER_UPDATES, ON,
          LOG_JDBC_ERRORS, OFF,
          LOG_JDBC_WARNINGS, OFF);

  /** The defaults that apply only with {@code stratum.backward-compatible=false}. */
  private static final Map<String, String> EXTERNAL_DEFAULTS =
      Map.of(OPEN_IN_VIEW, OFF, FAIL_ON_PAGINATION, ON);

  /** Every key the starter gives a default, with a value a service sets instead. */
  private static final List<Arguments> SERVICE_VALUES =
      List.of(
          arguments(IN_CLAUSE_PADDING, OFF),
          arguments(BATCH_SIZE, "20"),
          arguments(ORDER_INSERTS, OFF),
          arguments(ORDER_UPDATES, OFF),
          arguments(LOG_JDBC_ERRORS, ON),
          arguments(LOG_JDBC_WARNINGS, ON),
          arguments(OPEN_IN_VIEW, ON),
          arguments(FAIL_ON_PAGINATION, OFF));

  private final DataJpaEnvironmentPostProcessor postProcessor =
      new DataJpaEnvironmentPostProcessor();

  /** Runs the post-processor and returns the environment. */
  private MockEnvironment runPostProcessor(MockEnvironment environment) {
    postProcessor.postProcessEnvironment(environment, new SpringApplication());
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

  @Test
  @DisplayName("with the module off, it sets nothing")
  void setsNothingWithModuleOff() {
    var environment =
        runPostProcessor(new MockEnvironment().withProperty(DataConstants.ENABLED, OFF));

    assertUnset(environment, INTERNAL_DEFAULTS.keySet());
    assertUnset(environment, EXTERNAL_DEFAULTS.keySet());
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
    @FieldSource("com.exepex.stratum.datajpa.DataJpaEnvironmentPostProcessorTest#SERVICE_VALUES")
    @DisplayName("the service's own value wins")
    void keepsServiceValue(String key, String serviceValue) {
      var environment = runPostProcessor(new MockEnvironment().withProperty(key, serviceValue));

      assertThat(environment.getProperty(key)).isEqualTo(serviceValue);
    }
  }

  @Test
  @DisplayName("in backward-compatible mode, it sets only the defaults consumers cannot see")
  void setsOnlyInternalDefaultsInBackwardCompatibleMode() {
    var environment = runPostProcessor(new MockEnvironment().withProperty(BACKWARD_COMPATIBLE, ON));

    assertSet(environment, INTERNAL_DEFAULTS);
    assertUnset(environment, EXTERNAL_DEFAULTS.keySet());
  }
}
