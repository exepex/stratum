package com.exepex.stratum.cache.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.cache.testsupport.CacheAssertions;
import com.exepex.stratum.cache.testsupport.ProductCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheAspectSupport;
import org.springframework.context.annotation.Configuration;

@DisplayName("CachingAutoConfiguration switches Spring's caching on, so Spring Boot sets it up")
class CachingAutoConfigurationTest {

  /** A context with Spring Boot's cache set-up, Stratum's and a cached service. */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(CacheAutoConfiguration.class, CachingAutoConfiguration.class))
          .withBean(ProductCatalog.class);

  /** Asks twice for the same price and returns how many times it was really looked up. */
  private int lookupsForTwoCalls(AssertableApplicationContext context) {
    var catalog = context.getBean(ProductCatalog.class);
    catalog.priceOf("sku-1");
    catalog.priceOf("sku-1");
    return catalog.lookups();
  }

  /** Checks that caching works once and that Stratum's {@code @EnableCaching} stood back. */
  private void assertCachingFromElsewhere(AssertableApplicationContext context) {
    assertThat(context).hasSingleBean(CacheAspectSupport.class);
    assertThat(lookupsForTwoCalls(context)).isOne();
    CacheAssertions.assertLeftOut(context, CachingAutoConfiguration.class);
  }

  @Nested
  @DisplayName("with the module switched on or off")
  class ModuleSwitch {

    @Test
    @DisplayName(
        "with the module on, a cached method runs once and Spring Boot has a cache manager")
    void switchesCachingOn() {
      contextRunner.run(
          context -> {
            assertThat(context).hasSingleBean(CacheAspectSupport.class);
            assertThat(context).hasSingleBean(CacheManager.class);
            assertThat(lookupsForTwoCalls(context)).isOne();
          });
    }

    @Test
    @DisplayName("with the module off, caching stays off and the method runs every time")
    void leavesCachingOffWithModuleOff() {
      contextRunner
          .withPropertyValues(CacheConstants.ENABLED + "=false")
          .run(
              context -> {
                assertThat(context).doesNotHaveBean(CacheAspectSupport.class);
                assertThat(lookupsForTwoCalls(context)).isEqualTo(2);
              });
    }

    @Test
    @DisplayName("a module switch that is not a boolean stops startup")
    void failsOnBadSwitch() {
      contextRunner
          .withPropertyValues(CacheConstants.ENABLED + "=maybe")
          .run(context -> assertThat(context).hasFailed());
    }
  }

  @Nested
  @DisplayName("when caching is already switched on")
  class BackOff {

    @Test
    @DisplayName("the service's own @EnableCaching is kept and Stratum's stands back")
    void backsOffForServiceEnableCaching() {
      contextRunner
          .withUserConfiguration(ServiceCaching.class)
          .run(context -> assertCachingFromElsewhere(context));
    }

    @Test
    @DisplayName("a company framework's auto-configuration with no order wins over Stratum's")
    void backsOffForCompanyEnableCaching() {
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  CacheAutoConfiguration.class,
                  CachingAutoConfiguration.class,
                  CompanyCachingAutoConfiguration.class))
          .withBean(ProductCatalog.class)
          .run(context -> assertCachingFromElsewhere(context));
    }
  }

  /** A service that switches caching on itself. */
  @Configuration(proxyBeanMethods = false)
  @EnableCaching
  static class ServiceCaching {}

  /** A company framework's auto-configuration that switches caching on and declares no order. */
  @AutoConfiguration
  @EnableCaching
  static class CompanyCachingAutoConfiguration {}
}
