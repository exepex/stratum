package com.exepex.stratum.datajpa.autoconfigure;

import static com.exepex.stratum.test.ErrorCodeChecks.startupFailureOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.DataErrorCode;
import com.exepex.stratum.data.testsupport.RoutingPackagesCustomizer;
import com.exepex.stratum.datajpa.DataJpaConstants;
import com.exepex.stratum.datajpa.routing.OpenInViewCheck;
import com.exepex.stratum.datajpa.routing.RoutingRegistrar;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@DisplayName("DataJpaRoutingAutoConfiguration maps repositories to pools and checks open-in-view")
class DataJpaRoutingAutoConfigurationTest {

  /** The keys of the pool batch. */
  private static final String BATCH_KEYS = DataConstants.SOURCES + ".batch.";

  /** A URL for the pool batch, which no test connects to. */
  private static final String BATCH_URL = BATCH_KEYS + "url=jdbc:postgresql://batch-db:5432/batch";

  /** A mapping of a package that holds interfaces, though no JPA repository. */
  private static final String BATCH_PACKAGES =
      BATCH_KEYS + DataConstants.PACKAGES_KEY + "=java.util";

  /** A context with the starter's routing auto-configuration only. */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(DataJpaRoutingAutoConfiguration.class));

  /** A servlet web context with the starter's routing auto-configuration only. */
  private final WebApplicationContextRunner servletRunner =
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(DataJpaRoutingAutoConfiguration.class));

  @Nested
  @DisplayName("the registrar")
  class Registrar {

    @Test
    @DisplayName("is created with the module on")
    void created() {
      contextRunner.run(
          context ->
              assertThat(context.getBean(DataJpaConstants.ROUTING_REGISTRAR))
                  .isInstanceOf(RoutingRegistrar.class));
    }

    @Test
    @DisplayName("is not created with the module off")
    void moduleOff() {
      contextRunner
          .withPropertyValues(DataConstants.ENABLED + "=false")
          .run(context -> assertThat(context).doesNotHaveBean(DataJpaConstants.ROUTING_REGISTRAR));
    }

    @Test
    @DisplayName("backs off for the service's bean of its name")
    void backsOff() {
      contextRunner
          .withUserConfiguration(ServiceRegistrar.class)
          .run(
              context ->
                  assertThat(context.getBean(DataJpaConstants.ROUTING_REGISTRAR))
                      .isSameAs(ServiceRegistrar.REGISTRAR));
    }
  }

  @Nested
  @DisplayName("the mapping")
  class Mapping {

    @Test
    @DisplayName("without HikariCP, fails with SX-DATA-1007, since no named pool exists")
    void withoutHikari() {
      contextRunner
          .withClassLoader(new FilteredClassLoader(HikariDataSource.class))
          .withPropertyValues(BATCH_URL, BATCH_PACKAGES)
          .run(
              context ->
                  assertThat(startupFailureOf(context, DataErrorCode.ROUTING_INVALID))
                      .hasMessageContaining("batch.packages maps packages to a pool that does"));
    }

    @Test
    @DisplayName("with HikariCP, finds the pool and checks its packages, which hold no repository")
    void withHikari() {
      contextRunner
          .withPropertyValues(BATCH_URL, BATCH_PACKAGES)
          .run(
              context ->
                  assertThat(startupFailureOf(context, DataErrorCode.ROUTING_INVALID))
                      .hasMessageContaining("the package java.util of pool batch holds no JPA"));
    }

    @Test
    @DisplayName("takes the packages a Java customizer sets over the keys'")
    void javaWins() {
      contextRunner
          .withPropertyValues(
              BATCH_URL,
              BATCH_PACKAGES,
              DataConstants.QUERY_TIMEOUT
                  + "="
                  + RoutingPackagesCustomizer.MARKER.toMillis()
                  + "ms")
          .run(
              context ->
                  assertThat(startupFailureOf(context, DataErrorCode.ROUTING_INVALID))
                      .hasMessageContaining("batch.packages[0] is not a Java package name"));
    }
  }

  @Nested
  @DisplayName("the open-in-view check")
  class OpenInView {

    @Test
    @DisplayName("is created in a servlet web application")
    void createdForServlet() {
      servletRunner.run(context -> assertThat(context).hasSingleBean(OpenInViewCheck.class));
    }

    @Test
    @DisplayName("is not created outside a web application")
    void notOutsideWeb() {
      contextRunner.run(context -> assertThat(context).doesNotHaveBean(OpenInViewCheck.class));
    }

    @Test
    @DisplayName("is not created with the module off")
    void moduleOff() {
      servletRunner
          .withPropertyValues(DataConstants.ENABLED + "=false")
          .run(context -> assertThat(context).doesNotHaveBean(OpenInViewCheck.class));
    }

    @Test
    @DisplayName("backs off for the service's bean of its name")
    void backsOff() {
      servletRunner
          .withUserConfiguration(ServiceOpenInViewCheck.class)
          .run(
              context ->
                  assertThat(context)
                      .doesNotHaveBean(OpenInViewCheck.class)
                      .getBean(DataJpaConstants.OPEN_IN_VIEW_CHECK)
                      .isSameAs(ServiceOpenInViewCheck.CHECK));
    }

    @Test
    @DisplayName("with repositories mapped and open-in-view not set, fails with SX-DATA-1009")
    void mappedFails() {
      servletRunner
          .withUserConfiguration(MappedRepositories.class)
          .run(
              context ->
                  assertThat(startupFailureOf(context, DataErrorCode.OPEN_IN_VIEW_WITH_ROUTING))
                      .hasMessageContaining(DataJpaConstants.OPEN_IN_VIEW));
    }

    @Test
    @DisplayName("with repositories mapped and open-in-view off, starts")
    void mappedWithOpenInViewOffStarts() {
      servletRunner
          .withUserConfiguration(MappedRepositories.class)
          .withPropertyValues(DataJpaConstants.OPEN_IN_VIEW + "=false")
          .run(context -> assertThat(context).hasNotFailed());
    }
  }

  /** A service's own registrar, under the name of Stratum's. */
  @Configuration(proxyBeanMethods = false)
  static class ServiceRegistrar {

    /** The service's registrar, which defines nothing. */
    static final BeanDefinitionRegistryPostProcessor REGISTRAR =
        new BeanDefinitionRegistryPostProcessor() {
          @Override
          public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
            // The service's own registrar maps nothing in this test.
          }
        };

    /**
     * Returns the service's registrar.
     *
     * @return the registrar
     */
    @Bean(DataJpaConstants.ROUTING_REGISTRAR)
    // Static, because Spring creates registry post-processors before the other beans.
    static BeanDefinitionRegistryPostProcessor serviceRegistrar() {
      return REGISTRAR;
    }
  }

  /** A service's own open-in-view check, under the name of Stratum's. */
  @Configuration(proxyBeanMethods = false)
  static class ServiceOpenInViewCheck {

    /** The service's check, a plain object. */
    static final Object CHECK = new Object();

    /**
     * Returns the service's check.
     *
     * @return the check
     */
    @Bean(DataJpaConstants.OPEN_IN_VIEW_CHECK)
    Object serviceOpenInViewCheck() {
      return CHECK;
    }
  }

  /** Stands for a mapping: the bean whose presence shows that repositories are mapped. */
  @Configuration(proxyBeanMethods = false)
  static class MappedRepositories {

    /**
     * Returns the stand-in for the mapping.
     *
     * @return the stand-in
     */
    @Bean(DataJpaConstants.REPOSITORY_POOLS)
    Object stratumRepositoryPools() {
      return DataJpaConstants.REPOSITORY_POOLS;
    }
  }
}
