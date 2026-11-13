package com.exepex.stratum.datajpa.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.data.DataConstants;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("DataJpaBoundaryAutoConfiguration brings in the shared JDBC boundary")
class DataJpaBoundaryAutoConfigurationTest {

  /**
   * The names of every bean the shared configurations create with Flyway, Liquibase and PostgreSQL.
   */
  private static final List<String> BOUNDARY_BEANS =
      List.of(
          DataConstants.JDBC_BOUNDARY,
          DataConstants.LOGIN_TIMEOUT,
          DataConstants.TLS_CHECK,
          DataConstants.FLYWAY_BOUNDARY,
          DataConstants.LIQUIBASE_BOUNDARY);

  /** A context with the starter's boundary auto-configuration only. */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(DataJpaBoundaryAutoConfiguration.class));

  @Test
  @DisplayName("with the module on, every boundary bean of stratum-data is created")
  void importsEveryBoundaryBean() {
    contextRunner.run(
        context -> assertThat(context.getBeanDefinitionNames()).containsAll(BOUNDARY_BEANS));
  }

  @Test
  @DisplayName("with the module off, none is created")
  void importsNothingWithModuleOff() {
    contextRunner
        .withPropertyValues(DataConstants.ENABLED + "=false")
        .run(
            context ->
                assertThat(context.getBeanDefinitionNames())
                    .doesNotContainAnyElementsOf(BOUNDARY_BEANS));
  }
}
