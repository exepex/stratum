package com.exepex.stratum.data.autoconfigure;

import static com.exepex.stratum.data.testsupport.SqlFailureTestData.CONSTRAINT;
import static com.exepex.stratum.data.testsupport.SqlFailureTestData.postgresFailure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.DataPropertiesCustomizer;
import com.exepex.stratum.data.JdbcBoundaryPostProcessor;
import com.exepex.stratum.data.JdbcWrapper;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.hibernate.dialect.PostgreSQLDialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@DisplayName("JdbcBoundaryAutoConfiguration wraps every data source bean in the JDBC boundary")
class JdbcBoundaryAutoConfigurationTest {

  /** The name of Spring Boot's data source bean. */
  private static final String PRIMARY = "dataSource";

  /** The name of the service's own data source, which a team may skip. */
  private static final String LEGACY = "legacyDataSource";

  /** The name of a service's bean that hands out Spring Boot's data source under a second name. */
  private static final String ALIAS = "aliasDataSource";

  /** A context with the boundary and two data source beans, as a service with two pools has. */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(JdbcBoundaryAutoConfiguration.class))
          .withBean(PRIMARY, DataSource.class, this::failingDataSource)
          .withBean(LEGACY, DataSource.class, this::failingDataSource);

  /** Returns a data source whose every connection attempt fails with a unique violation. */
  private DataSource failingDataSource() {
    var dataSource = mock(DataSource.class);
    try {
      when(dataSource.getConnection()).thenThrow(postgresFailure("23505"));
    } catch (SQLException impossible) {
      throw new IllegalStateException(impossible);
    }
    return dataSource;
  }

  /** Checks that the context started without Stratum's post-processor and wrapped nothing. */
  private void assertNoDataSourceWrapped(
      AssertableApplicationContext context, Class<?> stratumPostProcessor) {
    assertThat(context).hasNotFailed().doesNotHaveBean(stratumPostProcessor);
    assertThat(context.getBean(PRIMARY)).isNotInstanceOf(JdbcWrapper.class);
  }

  /** Returns the constraint name Hibernate's PostgreSQL dialect reads from a bean's error. */
  private String constraintReadFrom(AssertableApplicationContext context, String beanName) {
    try {
      context.getBean(beanName, DataSource.class).getConnection();
      throw new AssertionError("the connection attempt did not fail");
    } catch (SQLException failure) {
      return new PostgreSQLDialect()
          .getViolatedConstraintNameExtractor()
          .extractConstraintName(failure);
    }
  }

  @Nested
  @DisplayName("with the module switched on or off")
  class ModuleSwitch {

    @Test
    @DisplayName("with the module on, every data source bean is wrapped and reads constraint names")
    void wrapsEveryDataSource() {
      contextRunner.run(context -> assertEveryDataSourceWrapped(context));
    }

    @Test
    @DisplayName(
        "a data source bean that returns an already wrapped data source is not wrapped again")
    void keepsWrappedDataSourceOfAlias() {
      contextRunner
          .withUserConfiguration(AliasConfiguration.class)
          .run(context -> assertThat(context.getBean(ALIAS)).isSameAs(context.getBean(PRIMARY)));
    }

    /** Checks that both data source beans are wrapped and still give the constraint's name. */
    private void assertEveryDataSourceWrapped(AssertableApplicationContext context) {
      assertThat(context).hasSingleBean(JdbcBoundaryPostProcessor.class);
      assertThat(context.getBean(PRIMARY)).isInstanceOf(JdbcWrapper.class);
      assertThat(context.getBean(LEGACY)).isInstanceOf(JdbcWrapper.class);
      assertThat(constraintReadFrom(context, PRIMARY)).isEqualTo(CONSTRAINT);
    }

    @Test
    @DisplayName("with the module off, no data source is wrapped")
    void wrapsNothingWithModuleOff() {
      contextRunner
          .withPropertyValues(DataConstants.ENABLED + "=false")
          .run(context -> assertNoDataSourceWrapped(context, JdbcBoundaryPostProcessor.class));
    }

    @Test
    @DisplayName("without the PostgreSQL driver, the context starts and no constraint name is read")
    void startsWithoutPostgresDriver() {
      contextRunner
          .withClassLoader(new FilteredClassLoader(PSQLException.class))
          .run(context -> assertThat(constraintReadFrom(context, PRIMARY)).isNull());
    }
  }

  @Nested
  @DisplayName("when the service or a company framework decides otherwise")
  class BackOff {

    @Test
    @DisplayName("a service's bean of the same name, with a filter, skips exactly the named bean")
    void skipsNamedDataSource() {
      contextRunner
          .withBean(
              DataConstants.JDBC_BOUNDARY,
              BeanPostProcessor.class,
              () -> new JdbcBoundaryPostProcessor(beanName -> !LEGACY.equals(beanName)))
          .run(context -> assertOnlyLegacySkipped(context));
    }

    /** Checks that the filter skipped the legacy data source and wrapped the other one. */
    private void assertOnlyLegacySkipped(AssertableApplicationContext context) {
      assertThat(context.getBean(PRIMARY)).isInstanceOf(JdbcWrapper.class);
      assertThat(context.getBean(LEGACY)).isNotInstanceOf(JdbcWrapper.class);
    }

    @Test
    @DisplayName("a company framework's auto-configuration with no order wins over Stratum's")
    void backsOffForCompanyBean() {
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  JdbcBoundaryAutoConfiguration.class, CompanyBoundaryAutoConfiguration.class))
          .withBean(
              PRIMARY, DataSource.class, JdbcBoundaryAutoConfigurationTest.this::failingDataSource)
          .run(context -> assertNoDataSourceWrapped(context, JdbcBoundaryPostProcessor.class));
    }

    @Test
    @DisplayName("a customizer declared as a bean stops startup with SX-CORE-1004")
    void customizerAsBeanStopsStartup() {
      contextRunner
          .withBean(DataPropertiesCustomizer.class, () -> properties -> properties)
          .run(
              context ->
                  assertThat(context.getStartupFailure())
                      .rootCause()
                      .hasMessageContaining("SX-CORE-1004"));
    }

    @Test
    @DisplayName("a service's post-processor with a filter of null is refused")
    void rejectsNullFilter() {
      assertThatThrownBy(() -> new JdbcBoundaryPostProcessor(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("wrapsBean must not be null");
    }
  }

  @Nested
  @DisplayName("the thread that cancels statements past their limit")
  class SweeperThread {

    /** The name of the thread. */
    private static final String SWEEPER = "stratum-statement-sweeper";

    /** How long the thread may take to stop once the application has stopped. */
    private static final Duration STOP_WAIT = Duration.ofSeconds(5);

    /** Returns whether a sweeper thread is alive. */
    private boolean sweeperAlive() {
      return Thread.getAllStackTraces().keySet().stream()
          .anyMatch(thread -> SWEEPER.equals(thread.getName()) && thread.isAlive());
    }

    @Test
    @DisplayName("starts with the first wrapped data source and stops with the application")
    void stopsWithApplication() {
      contextRunner.run(
          context -> {
            context.getBean(PRIMARY);
            assertThat(sweeperAlive()).isTrue();
          });

      await().atMost(STOP_WAIT).until(() -> !sweeperAlive());
    }
  }

  /** A service's configuration that hands out Spring Boot's data source under a second name. */
  @Configuration(proxyBeanMethods = false)
  static class AliasConfiguration {

    /**
     * Returns Spring Boot's data source, which the boundary has already wrapped.
     *
     * @param dataSource Spring Boot's data source bean
     * @return the same data source
     */
    @Bean(ALIAS)
    DataSource aliasDataSource(@Qualifier(PRIMARY) DataSource dataSource) {
      return dataSource;
    }
  }

  /** A company framework's auto-configuration that declares no order and its own post-processor. */
  @AutoConfiguration
  static class CompanyBoundaryAutoConfiguration {

    /**
     * Returns the company's post-processor, which leaves every bean as it is.
     *
     * @return the post-processor
     */
    @Bean(DataConstants.JDBC_BOUNDARY)
    // Static, because Spring creates bean post-processors before the other beans.
    static BeanPostProcessor companyBoundary() {
      return new BeanPostProcessor() {};
    }
  }
}
