package com.exepex.stratum.datajpa.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.JdbcBoundaryPostProcessor;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

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

  @Test
  @DisplayName("a company framework's auto-configuration with no order wins over Stratum's")
  void backsOffForCompanyBean() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DataJpaBoundaryAutoConfiguration.class, CompanyBoundaryAutoConfiguration.class))
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(JdbcBoundaryPostProcessor.class)
                    .getBean(DataConstants.JDBC_BOUNDARY)
                    .isSameAs(CompanyBoundaryAutoConfiguration.COMPANY_BOUNDARY));
  }

  /** A company framework's auto-configuration that declares no order and its own post-processor. */
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
