package com.exepex.stratum.cache.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.cache.CacheCounters;
import com.exepex.stratum.cache.StratumCacheErrorHandler;
import com.exepex.stratum.cache.StratumCacheLayerHook;
import com.exepex.stratum.cache.StratumCachePropertiesCustomizer;
import com.exepex.stratum.cache.StratumCacheResolver;
import com.exepex.stratum.cache.testsupport.ProductCatalog;
import com.exepex.stratum.cache.testsupport.RecordingCacheManager;
import com.exepex.stratum.test.LogLineCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.cache.interceptor.SimpleCacheErrorHandler;
import org.springframework.cache.interceptor.SimpleCacheResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@DisplayName(
    "CacheLayerAutoConfiguration installs the fail-open layer, and stands back for the"
        + " service's own caching set-up")
class CacheLayerAutoConfigurationTest {

  /** A context with Spring Boot's cache set-up, Stratum's caching and the layer. */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  CacheAutoConfiguration.class,
                  StratumCacheAutoConfiguration.class,
                  CacheLayerAutoConfiguration.class))
          .withBean(ProductCatalog.class);

  /** Asks twice for the same price and returns how many times it was really looked up. */
  private int lookupsForTwoCalls(AssertableApplicationContext context) {
    var catalog = context.getBean(ProductCatalog.class);
    catalog.priceOf("sku-1");
    catalog.priceOf("sku-1");
    return catalog.lookups();
  }

  @Nested
  @DisplayName("with the module on")
  class ModuleOn {

    @Test
    @DisplayName("the interceptor gets Stratum's resolver and handler, and caching still works")
    void installsTheLayer() {
      contextRunner.run(
          context -> {
            var interceptor = context.getBean(CacheInterceptor.class);
            assertThat(interceptor.getCacheResolver()).isInstanceOf(StratumCacheResolver.class);
            assertThat(interceptor.getErrorHandler()).isInstanceOf(StratumCacheErrorHandler.class);
            assertThat(lookupsForTwoCalls(context)).isOne();
          });
    }

    @Test
    @DisplayName(
        "with a cap of one cache, a second cache's method runs every time and one"
            + " SX-CACHE-1003 warns of the refusal")
    void refusesOverTheCap() {
      var capture = new LogLineCapture(StratumCacheResolver.class.getName());
      capture.start();
      try {
        contextRunner
            .withPropertyValues(
                CacheConstants.MAX_CACHES + "=1", CacheConstants.WARNING_INTERVAL + "=1h")
            .run(
                context -> {
                  var catalog = context.getBean(ProductCatalog.class);
                  catalog.priceOf("sku-1");
                  catalog.priceOf("sku-1");
                  assertThat(catalog.lookups()).isOne();

                  catalog.syncedPriceOf("sku-1");
                  catalog.syncedPriceOf("sku-1");
                  assertThat(catalog.syncedLookups()).isEqualTo(2);
                  assertThat(catalog.syncedPriceOf("sku-1")).isEqualTo("synced-price-of-sku-1");
                });
      } finally {
        capture.stop();
      }

      assertThat(capture.lines()).hasSize(1);
      assertThat(capture.lines().getFirst().getFormattedMessage())
          .contains("SX-CACHE-1003")
          .contains(ProductCatalog.SYNCED_PRODUCTS);
    }

    @Test
    @DisplayName("with spring.main.lazy-initialization=true, the layer is still installed")
    void worksWithLazyInitialization() {
      try (var context =
          new SpringApplicationBuilder(LazyApplication.class)
              .web(WebApplicationType.NONE)
              // spring.cache.type pins the map-based manager: with Redis on the test classpath a
              // real app would otherwise pick a Redis cache manager pointed at no Redis.
              .properties(
                  "spring.main.lazy-initialization=true",
                  "spring.application.name=lazy",
                  "spring.cache.type=simple")
              .run()) {
        var interceptor = context.getBean(CacheInterceptor.class);
        assertThat(interceptor.getCacheResolver()).isInstanceOf(StratumCacheResolver.class);
        assertThat(interceptor.getErrorHandler()).isInstanceOf(StratumCacheErrorHandler.class);
        var catalog = context.getBean(ProductCatalog.class);
        catalog.priceOf("sku-1");
        catalog.priceOf("sku-1");
        assertThat(catalog.lookups()).isOne();
      }
    }

    @Test
    @DisplayName("without Micrometer, the counters are no-ops and the layer still works")
    void worksWithoutMicrometer() {
      contextRunner
          .withClassLoader(new FilteredClassLoader("io.micrometer"))
          .run(
              context -> {
                assertThat(context.getBean(CacheCounters.class))
                    .isInstanceOf(CacheCounters.Noop.class);
                var interceptor = context.getBean(CacheInterceptor.class);
                assertThat(interceptor.getCacheResolver()).isInstanceOf(StratumCacheResolver.class);
                assertThat(lookupsForTwoCalls(context)).isOne();
              });
    }
  }

  @Nested
  @DisplayName("with the module off or its own set-up")
  class StandsBack {

    @Test
    @DisplayName("with the module off, a service's caching keeps Spring's behaviour")
    void moduleOffKeepsSpringsBehaviour() {
      contextRunner
          .withPropertyValues(CacheConstants.ENABLED + "=false")
          .withUserConfiguration(ServiceCaching.class)
          .run(
              context -> {
                assertThat(context).doesNotHaveBean(StratumCacheLayerHook.class);
                var interceptor = context.getBean(CacheInterceptor.class);
                // Spring fills an unset resolver after the singletons; never with Stratum's.
                assertThat(interceptor.getCacheResolver())
                    .isNotInstanceOf(StratumCacheResolver.class);
                assertThat(interceptor.getErrorHandler())
                    .isInstanceOf(SimpleCacheErrorHandler.class);
                assertThat(lookupsForTwoCalls(context)).isOne();
              });
    }

    @Test
    @DisplayName("a service's cache resolver wins: the hook sets nothing")
    void serviceResolverWins() {
      var serviceResolver = (CacheResolver) context -> java.util.List.of();
      contextRunner
          .withBean(CachingConfigurer.class, () -> configurerWithResolver(serviceResolver))
          .run(
              context -> {
                var interceptor = context.getBean(CacheInterceptor.class);
                assertThat(interceptor.getCacheResolver()).isSameAs(serviceResolver);
                assertThat(interceptor.getErrorHandler())
                    .isNotInstanceOf(StratumCacheErrorHandler.class);
              });
    }

    @Test
    @DisplayName("a service's cache manager alone, which Spring turns into a resolver, wins too")
    void serviceCacheManagerWins() {
      contextRunner
          .withBean(
              CachingConfigurer.class,
              () ->
                  new CachingConfigurer() {
                    @Override
                    public CacheManager cacheManager() {
                      return new ConcurrentMapCacheManager();
                    }
                  })
          .run(
              context -> {
                var interceptor = context.getBean(CacheInterceptor.class);
                assertThat(interceptor.getCacheResolver()).isInstanceOf(SimpleCacheResolver.class);
                assertThat(interceptor.getErrorHandler())
                    .isNotInstanceOf(StratumCacheErrorHandler.class);
              });
    }

    @Test
    @DisplayName(
        "with no cache interceptor (AspectJ mode), the context starts and the layer is" + " off")
    void noInterceptorStartsFine() {
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(CacheLayerAutoConfiguration.class))
          .withBean(CacheManager.class, RecordingCacheManager::new)
          .run(
              context -> {
                assertThat(context).hasSingleBean(StratumCacheLayerHook.class);
                assertThat(context).doesNotHaveBean(CacheInterceptor.class);
              });
    }

    @Test
    @DisplayName("a customizer declared as a bean stops startup with SX-CORE-1004")
    void customizerAsBeanStopsStartup() {
      contextRunner
          .withBean(StratumCachePropertiesCustomizer.class, () -> properties -> properties)
          .run(
              context ->
                  assertThat(context.getStartupFailure())
                      .rootCause()
                      .hasMessageContaining("SX-CORE-1004"));
    }
  }

  /** Returns a configurer that sets only the given cache resolver. */
  private static CachingConfigurer configurerWithResolver(CacheResolver resolver) {
    return new CachingConfigurer() {
      @Override
      public CacheResolver cacheResolver() {
        return resolver;
      }
    };
  }

  /** A service that switches caching on itself. */
  @Configuration(proxyBeanMethods = false)
  @EnableCaching
  static class ServiceCaching {}

  /**
   * A service with lazy initialization, caching its catalog. It scans nothing: the
   * auto-configurations arrive over their imports file, as in a real service.
   */
  @SpringBootApplication(scanBasePackages = {})
  static class LazyApplication {

    /**
     * Creates the cached catalog.
     *
     * @return the catalog
     */
    @Bean
    ProductCatalog productCatalog() {
      return new ProductCatalog();
    }
  }
}
