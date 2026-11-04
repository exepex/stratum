package com.exepex.stratum.data.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.FlywayBoundaryCustomizer;
import com.exepex.stratum.data.LiquibaseBoundaryPostProcessor;
import java.util.List;
import liquibase.integration.spring.SpringLiquibase;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

@ParameterizedClass
@FieldSource("TOOLS")
@DisplayName("Flyway's and Liquibase's boundary beans follow the module switch and back off")
class MigrationBoundaryAutoConfigurationTest {

  /**
   * Each migration tool: its auto-configuration, the name and type of Stratum's bean, and the class
   * whose absence means the service does not use the tool.
   */
  private static final List<Arguments> TOOLS =
      List.of(
          argumentSet(
              "Flyway",
              MigrationTool.builder()
                  .autoConfiguration(FlywayBoundaryAutoConfiguration.class)
                  .beanName(DataConstants.FLYWAY_BOUNDARY)
                  .beanType(FlywayBoundaryCustomizer.class)
                  .toolClass(FlywayConfigurationCustomizer.class)
                  .build()),
          argumentSet(
              "Liquibase",
              MigrationTool.builder()
                  .autoConfiguration(LiquibaseBoundaryAutoConfiguration.class)
                  .beanName(DataConstants.LIQUIBASE_BOUNDARY)
                  .beanType(LiquibaseBoundaryPostProcessor.class)
                  .toolClass(SpringLiquibase.class)
                  .build()));

  /** The migration tool under test. */
  @Parameter private MigrationTool tool;

  /** Returns a context with the tool's auto-configuration only. */
  private ApplicationContextRunner contextRunner() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(tool.autoConfiguration()));
  }

  @Nested
  @DisplayName("with the module switched on or off")
  class ModuleSwitch {

    @Test
    @DisplayName("with the module on, Stratum's bean is created under its name")
    void createsBean() {
      contextRunner()
          .run(
              context ->
                  assertThat(context).getBean(tool.beanName()).isInstanceOf(tool.beanType()));
    }

    @Test
    @DisplayName("with the module off, no bean is created")
    void createsNothingWithModuleOff() {
      contextRunner()
          .withPropertyValues(DataConstants.ENABLED + "=false")
          .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(tool.beanName()));
    }

    @Test
    @DisplayName("without the migration tool on the class path, no bean is created")
    void createsNothingWithoutTool() {
      contextRunner()
          .withClassLoader(new FilteredClassLoader(tool.toolClass()))
          .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(tool.beanName()));
    }
  }

  @Nested
  @DisplayName("when the service or a company framework decides otherwise")
  class BackOff {

    @Test
    @DisplayName("a service's bean of the same name replaces Stratum's")
    void backsOffForServiceBean() {
      contextRunner()
          .withBean(tool.beanName(), Object.class, Object::new)
          .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(tool.beanType()));
    }

    @Test
    @DisplayName("a company framework's auto-configuration with no order wins over Stratum's")
    void backsOffForCompanyBean() {
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  tool.autoConfiguration(), CompanyMigrationAutoConfiguration.class))
          .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(tool.beanType()));
    }
  }

  /**
   * A migration tool's set-up.
   *
   * @param autoConfiguration Stratum's auto-configuration for the tool
   * @param beanName the name of Stratum's bean
   * @param beanType the type of Stratum's bean
   * @param toolClass a class whose absence means the service does not use the tool
   */
  @Builder
  record MigrationTool(
      Class<?> autoConfiguration, String beanName, Class<?> beanType, Class<?> toolClass) {}

  /** A company framework's auto-configuration that declares no order and both beans by name. */
  @AutoConfiguration
  static class CompanyMigrationAutoConfiguration {

    /**
     * Returns the company's Flyway bean, which does nothing.
     *
     * @return the bean
     */
    @Bean(DataConstants.FLYWAY_BOUNDARY)
    Object companyFlywayBoundary() {
      return new Object();
    }

    /**
     * Returns the company's Liquibase bean, which does nothing.
     *
     * @return the bean
     */
    @Bean(DataConstants.LIQUIBASE_BOUNDARY)
    Object companyLiquibaseBoundary() {
      return new Object();
    }
  }
}
