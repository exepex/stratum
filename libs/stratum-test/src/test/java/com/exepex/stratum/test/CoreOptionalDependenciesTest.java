package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.util.ClassUtils;

@DisplayName("stratum-core keeps its actuator and health dependencies optional")
class CoreOptionalDependenciesTest {

  /**
   * A class from each of stratum-core's optional dependencies, spring-boot-actuator-autoconfigure
   * and spring-boot-health, which this module, built on stratum-core without actuator, never gets.
   */
  private static final List<String> OPTIONAL_DEPENDENCY_CLASSES =
      List.of(
          "org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties",
          "org.springframework.boot.health.autoconfigure.actuate.endpoint."
              + "HealthEndpointProperties");

  @ParameterizedTest
  @FieldSource("OPTIONAL_DEPENDENCY_CLASSES")
  @DisplayName("a module that depends on stratum-core does not get them")
  void notBroughtByCore(String className) {
    assertThat(ClassUtils.isPresent(className, null))
        .as(
            "%s is on the classpath: stratum-core must keep spring-boot-actuator-autoconfigure"
                + " and spring-boot-health optional",
            className)
        .isFalse();
  }
}
