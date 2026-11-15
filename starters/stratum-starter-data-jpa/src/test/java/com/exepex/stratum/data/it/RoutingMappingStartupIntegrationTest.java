package com.exepex.stratum.data.it;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.DataErrorCode;
import com.exepex.stratum.data.it.pools.PoolsApplication;
import com.exepex.stratum.data.testsupport.PoolDatabases;
import com.exepex.stratum.data.testsupport.ServiceDataSources;
import com.exepex.stratum.test.PostgresContainerConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@DisplayName(
    "A mapping of repositories to pools that breaks a rule stops startup with SX-DATA-1007")
class RoutingMappingStartupIntegrationTest {

  /** The key of the pool {@code batch}'s packages. */
  private static final String BATCH_PACKAGES = "stratum.data.sources.batch.packages=";

  /** Returns the service of the pools tests, with the pool batch's database, and more sources. */
  private SpringApplicationBuilder pools(Class<?>... sources) {
    return new SpringApplicationBuilder(
            PoolsApplication.class, PostgresContainerConfiguration.class)
        .sources(sources)
        .initializers(new PoolDatabases.BatchDatabase())
        .web(WebApplicationType.NONE)
        .properties(PoolDatabases.SQL_INIT, PoolDatabases.SCHEMA);
  }

  /** Checks that the service stops with SX-DATA-1007 and a message that names the cause. */
  private void assertStopsWith(SpringApplicationBuilder application, String detail) {
    assertThatThrownBy(application::run)
        .hasStackTraceContaining(DataErrorCode.ROUTING_INVALID.getCode())
        .hasStackTraceContaining(detail);
  }

  @Nested
  @DisplayName("the packages")
  class Packages {

    /** Entries that are no package name. */
    private static final List<String> NOT_PACKAGES =
        List.of("com..acme", "com.exepex.stratum.data.it.pools.*", ".com.acme");

    @ParameterizedTest(name = "{0}")
    @FieldSource("NOT_PACKAGES")
    @DisplayName("an entry that is no package name fails, named by its index, not its value")
    void notPackageName(String entry) {
      assertStopsWith(
          pools().properties(BATCH_PACKAGES + entry),
          "stratum.data.sources.batch.packages[0] is not a Java package name");
    }

    @Test
    @DisplayName("a package mapped to two pools fails")
    void twoPools() {
      assertStopsWith(
          pools()
              .properties(
                  PoolDatabases.MAPPING,
                  "stratum.data.sources.reporting.url=jdbc:postgresql://localhost:1/r",
                  "stratum.data.sources.reporting.packages=com.exepex.stratum.data.it.pools.batch"),
          "is mapped to the pools batch and reporting");
    }

    @Test
    @DisplayName("a package that holds no JPA repository fails, as a typo would")
    void noRepository() {
      assertStopsWith(
          pools().properties(BATCH_PACKAGES + "com.exepex.stratum.data.it.pools.bacth"),
          "the package com.exepex.stratum.data.it.pools.bacth of pool batch holds no JPA");
    }
  }

  @Nested
  @DisplayName("the annotations")
  class Annotations {

    @Test
    @DisplayName("@UseDataSource naming a pool that does not exist fails")
    void unknownPool() {
      assertStopsWith(
          new SpringApplicationBuilder(PoolsApplication.class)
              .web(WebApplicationType.NONE)
              .properties("spring.datasource.url=jdbc:postgresql://localhost:1/orders"),
          "names the pool batch, which does not exist");
    }
  }

  @Nested
  @DisplayName("the primary data source")
  class PrimaryDataSource {

    @Test
    @DisplayName("a service's own plain DataSource fails, as Spring Boot then creates none")
    void ownDataSource() {
      assertStopsWith(
          pools(ServiceDataSources.Plain.class).properties(PoolDatabases.MAPPING),
          "the bean dataSource is not Spring Boot's");
    }

    @Test
    @DisplayName("a service's own @Primary DataSource fails")
    void ownPrimary() {
      assertStopsWith(
          pools(ServiceDataSources.OwnPrimary.class).properties(PoolDatabases.MAPPING),
          "the bean dataSource is not Spring Boot's");
    }

    @Test
    @DisplayName("a primary DataSource a framework registers beside Spring Boot's fails")
    void frameworkPrimary() {
      assertStopsWith(
          pools(FrameworkPrimary.class).properties(PoolDatabases.MAPPING),
          "the bean frameworkDataSource is another primary DataSource");
    }

    @Test
    @DisplayName("a bean named stratumRoutingDataSource fails")
    void routingBeanName() {
      assertStopsWith(
          pools(OwnRoutingName.class).properties(PoolDatabases.MAPPING),
          "the service defines a bean named " + DataConstants.ROUTING_BEAN);
    }
  }

  /** A framework that registers a primary data source once the configuration is read. */
  @Configuration(proxyBeanMethods = false)
  static class FrameworkPrimary {

    /**
     * Returns the registrar of the framework's primary pool.
     *
     * @return the registrar
     */
    @Bean
    static BeanDefinitionRegistryPostProcessor frameworkRegistrar() {
      return new FrameworkRegistrar();
    }
  }

  /** Registers a primary pool, as a framework's own registrar would. */
  static class FrameworkRegistrar implements BeanDefinitionRegistryPostProcessor {

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
      var definition = new RootBeanDefinition(HikariDataSource.class);
      definition.setPrimary(true);
      registry.registerBeanDefinition("frameworkDataSource", definition);
    }
  }

  /** A service bean of the routing data source's name. */
  @Configuration(proxyBeanMethods = false)
  static class OwnRoutingName {

    /**
     * Returns a bean that only takes the name.
     *
     * @return the bean
     */
    @Bean(DataConstants.ROUTING_BEAN)
    String stratumRoutingDataSource() {
      return "taken";
    }
  }
}
