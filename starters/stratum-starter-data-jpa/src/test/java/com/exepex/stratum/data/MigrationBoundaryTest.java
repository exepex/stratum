package com.exepex.stratum.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

@DisplayName("Flyway's and Liquibase's own connections get the boundary only when it is theirs")
class MigrationBoundaryTest {

  /**
   * Returns the post-processors of a bean factory holding the given number of Stratum's
   * post-processors, each a fallback bean as Stratum declares it.
   */
  private ObjectProvider<JdbcBoundaryPostProcessor> fallbackPostProcessors(int count) {
    var beanFactory = new DefaultListableBeanFactory();
    IntStream.range(0, count)
        .forEach(index -> beanFactory.registerBeanDefinition("boundary" + index, fallback()));
    return beanFactory.getBeanProvider(JdbcBoundaryPostProcessor.class);
  }

  /** Returns the definition of a post-processor that is a fallback bean, as Stratum's is. */
  private RootBeanDefinition fallback() {
    var definition =
        new RootBeanDefinition(
            JdbcBoundaryPostProcessor.class, () -> new JdbcBoundaryPostProcessor(name -> true));
    definition.setFallback(true);
    return definition;
  }

  /** Returns the data source Flyway uses after Stratum's customizer has run. */
  private DataSource flywayDataSource(
      ObjectProvider<JdbcBoundaryPostProcessor> postProcessors, DataSource chosen) {
    var configuration = new FluentConfiguration().dataSource(chosen);
    new FlywayBoundaryCustomizer(postProcessors).customize(configuration);
    return configuration.getDataSource();
  }

  /** Returns the data source Liquibase uses after Stratum's post-processor has run. */
  private DataSource liquibaseDataSource(
      ObjectProvider<JdbcBoundaryPostProcessor> postProcessors, DataSource chosen) {
    var liquibase = new SpringLiquibase();
    liquibase.setDataSource(chosen);
    new LiquibaseBoundaryPostProcessor(postProcessors)
        .postProcessBeforeInitialization(liquibase, "liquibase");
    return liquibase.getDataSource();
  }

  @Nested
  @DisplayName("Flyway")
  class Flyway {

    @Test
    @DisplayName("keeps a data source bean, which has its boundary already, as it is")
    void keepsDataSourceBean() {
      var bean = new DriverManagerDataSource();

      assertThat(flywayDataSource(fallbackPostProcessors(1), bean)).isSameAs(bean);
    }

    @Test
    @DisplayName("wraps nothing when no single post-processor gives the boundary")
    void wrapsNothingWithoutSinglePostProcessor() {
      var connection = new SimpleDriverDataSource();

      assertThat(flywayDataSource(fallbackPostProcessors(2), connection)).isSameAs(connection);
    }
  }

  @Nested
  @DisplayName("Liquibase")
  class Liquibase {

    @Test
    @DisplayName("keeps a data source bean, which has its boundary already, as it is")
    void keepsDataSourceBean() {
      var bean = new DriverManagerDataSource();

      assertThat(liquibaseDataSource(fallbackPostProcessors(1), bean)).isSameAs(bean);
    }

    @Test
    @DisplayName("wraps nothing when no single post-processor gives the boundary")
    void wrapsNothingWithoutSinglePostProcessor() {
      var connection = new SimpleDriverDataSource();

      assertThat(liquibaseDataSource(fallbackPostProcessors(2), connection)).isSameAs(connection);
    }
  }
}
