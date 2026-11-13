package com.exepex.stratum.data.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.ClassUtils;

/**
 * Guards the integration tests' class path: with the JDBC starter on it, its {@code
 * spring.factories} and auto-configuration imports would stand in for this starter's own, and the
 * other integration tests would pass with them missing.
 */
@DisplayName("The data JPA starter's integration tests run without the JDBC starter")
class DataJpaClassPathIntegrationTest {

  @Test
  @DisplayName("the JDBC starter's auto-configuration is not on the class path")
  void runsWithoutJdbcStarter() {
    assertThat(
            ClassUtils.isPresent(
                "com.exepex.stratum.jdbc.autoconfigure.JdbcStarterAutoConfiguration", null))
        .isFalse();
  }
}
