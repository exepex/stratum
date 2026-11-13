package com.exepex.stratum.jdbc.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.DataProperties;
import com.exepex.stratum.data.JdbcBoundaryPostProcessor;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.List;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.postgresql.Driver;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

@DisplayName("JdbcStarterAutoConfiguration brings in the shared JDBC boundary")
class JdbcStarterAutoConfigurationTest {

  /**
   * The names of every bean the shared configurations create with Flyway, Liquibase and PostgreSQL.
   */
  // Copied from DataJpaBoundaryAutoConfigurationTest; the owner allowed it (ST-ENG-06).
  private static final List<String> BOUNDARY_BEANS =
      List.of(
          DataConstants.JDBC_BOUNDARY,
          DataConstants.LOGIN_TIMEOUT,
          DataConstants.TLS_CHECK,
          DataConstants.FLYWAY_BOUNDARY,
          DataConstants.LIQUIBASE_BOUNDARY);

  /** A context with the starter's auto-configuration only. */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(JdbcStarterAutoConfiguration.class));

  @Nested
  @DisplayName("with the module switched on or off")
  class ModuleSwitch {

    @Test
    @DisplayName("with stratum.data.enabled unset, every boundary bean of stratum-data is created")
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

  @Nested
  @DisplayName("when the service or a company framework defines the bean")
  class BackOff {

    @Test
    @DisplayName("a service's bean of the same name replaces Stratum's boundary")
    void backsOffForServiceBean() {
      contextRunner
          .withBean(
              DataConstants.JDBC_BOUNDARY,
              BeanPostProcessor.class,
              () -> CompanyBoundaryAutoConfiguration.COMPANY_BOUNDARY)
          .run(
              context ->
                  assertThat(context)
                      .hasNotFailed()
                      .doesNotHaveBean(JdbcBoundaryPostProcessor.class));
    }

    @Test
    @DisplayName("a company framework's auto-configuration with no order wins over Stratum's")
    void backsOffForCompanyBean() {
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  JdbcStarterAutoConfiguration.class, CompanyBoundaryAutoConfiguration.class))
          .run(
              context ->
                  assertThat(context)
                      .hasNotFailed()
                      .doesNotHaveBean(JdbcBoundaryPostProcessor.class)
                      .getBean(DataConstants.JDBC_BOUNDARY)
                      .isSameAs(CompanyBoundaryAutoConfiguration.COMPANY_BOUNDARY));
    }
  }

  @Nested
  @DisplayName("without an optional library on the class path")
  class MissingLibrary {

    @Test
    @DisplayName("without Flyway, there is no Flyway boundary and the rest is created")
    void startsWithoutFlyway() {
      assertMissingOnly(FlywayConfigurationCustomizer.class, DataConstants.FLYWAY_BOUNDARY);
    }

    @Test
    @DisplayName("without Liquibase, there is no Liquibase boundary and the rest is created")
    void startsWithoutLiquibase() {
      assertMissingOnly(SpringLiquibase.class, DataConstants.LIQUIBASE_BOUNDARY);
    }

    @Test
    @DisplayName("without the PostgreSQL driver, there is no login limit and the rest is created")
    void startsWithoutPostgresDriver() {
      assertMissingOnly(Driver.class, DataConstants.LOGIN_TIMEOUT);
    }

    @Test
    @DisplayName("without HikariCP, there is no login limit and the rest is created")
    void startsWithoutHikari() {
      assertMissingOnly(HikariDataSource.class, DataConstants.LOGIN_TIMEOUT);
    }

    /** Checks that, without one class, only the bean that needs it is missing. */
    private void assertMissingOnly(Class<?> missingClass, String missingBean) {
      contextRunner
          .withClassLoader(new FilteredClassLoader(missingClass))
          .run(context -> assertOnlyBeanMissing(context, missingBean));
    }

    /** Checks that the context started with every boundary bean but the missing one. */
    private void assertOnlyBeanMissing(AssertableApplicationContext context, String missingBean) {
      assertThat(context).hasNotFailed();
      assertThat(context.getBeanDefinitionNames())
          .doesNotContain(missingBean)
          .containsAll(BOUNDARY_BEANS.stream().filter(bean -> !bean.equals(missingBean)).toList());
    }
  }

  @Nested
  @DisplayName("with settings from application.properties or application.yml")
  class PropertyBinding {

    /** The two files, in each format, that set the statement limit to 5 seconds. */
    private static final List<String> CONFIG_NAMES = List.of("jdbc-properties", "jdbc-yaml");

    /** The statement limit both files set. */
    private static final Duration FILES_QUERY_TIMEOUT = Duration.ofSeconds(5);

    @ParameterizedTest(name = "{0}")
    @FieldSource("CONFIG_NAMES")
    @DisplayName("the service's statement limit is bound")
    void bindsStatementLimit(String configName) {
      contextRunner
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withPropertyValues("spring.config.name=" + configName)
          .run(
              context ->
                  assertThat(DataProperties.resolve(context.getEnvironment()).queryTimeout())
                      .isEqualTo(FILES_QUERY_TIMEOUT));
    }

    @Test
    @DisplayName("a module switch that is not a boolean stops startup")
    void failsOnBadSwitch() {
      contextRunner
          .withPropertyValues(DataConstants.ENABLED + "=maybe")
          .run(context -> assertThat(context).hasFailed());
    }
  }

  /** A company framework's auto-configuration that declares no order and its own post-processor. */
  // A copy of DataJpaBoundaryAutoConfigurationTest's fixture, kept by the owner's decision.
  @AutoConfiguration
  static class CompanyBoundaryAutoConfiguration {

    /** The company's post-processor, which leaves every bean as it is. */
    static final BeanPostProcessor COMPANY_BOUNDARY = new BeanPostProcessor() {};

    /**
     * Returns the company's post-processor under the name of Stratum's boundary.
     *
     * @return the post-processor
     */
    @Bean(DataConstants.JDBC_BOUNDARY)
    // Static, because Spring creates bean post-processors before the other beans.
    static BeanPostProcessor companyBoundary() {
      return COMPANY_BOUNDARY;
    }
  }
}
