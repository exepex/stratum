package com.exepex.stratum.data.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.SchemaValidationDefault;
import com.exepex.stratum.data.testsupport.DataTestData;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernateProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.jpa.autoconfigure.JpaProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@DisplayName(
    "DataJpaAutoConfiguration creates the schema default when Spring Boot sets up Hibernate")
class DataJpaAutoConfigurationTest {

  /** A context with what Spring Boot's Hibernate set-up provides: its settings and a database. */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(HibernateSetUp.class)
          .withConfiguration(AutoConfigurations.of(DataJpaAutoConfiguration.class));

  @Nested
  @DisplayName("with the module switched on or off")
  class ModuleSwitch {

    @Test
    @DisplayName("with the module on, the schema default is created")
    void createsSchemaDefault() {
      contextRunner.run(
          context -> assertThat(context).hasSingleBean(SchemaValidationDefault.class));
    }

    @Test
    @DisplayName("with the module off, nothing is created")
    void createsNothingWithModuleOff() {
      contextRunner
          .withPropertyValues(DataConstants.ENABLED + "=false")
          .run(context -> assertThat(context).doesNotHaveBean(SchemaValidationDefault.class));
    }
  }

  @Nested
  @DisplayName("when the service or Spring Boot decides otherwise")
  class BackOff {

    @Test
    @DisplayName("without Spring Boot's Hibernate set-up, nothing is created")
    void createsNothingWithoutHibernate() {
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(DataJpaAutoConfiguration.class))
          .run(context -> assertThat(context).doesNotHaveBean(SchemaValidationDefault.class));
    }

    @Test
    @DisplayName("a service's bean of the same name replaces it")
    void backsOffForServiceBean() {
      contextRunner
          .withBean(
              DataConstants.SCHEMA_VALIDATION_DEFAULT,
              HibernatePropertiesCustomizer.class,
              () -> hibernateSettings -> hibernateSettings.put("hibernate.hbm2ddl.auto", "none"))
          .run(context -> assertThat(context).doesNotHaveBean(SchemaValidationDefault.class));
    }
  }

  /** What Spring Boot's Hibernate set-up provides to the module. */
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({JpaProperties.class, HibernateProperties.class})
  static class HibernateSetUp {

    /**
     * Returns a database on a server, which the schema default checks.
     *
     * @return the database
     * @throws SQLException never; the mock declares it
     */
    @Bean
    DataSource dataSource() throws SQLException {
      return DataTestData.serverDatabase();
    }
  }
}
