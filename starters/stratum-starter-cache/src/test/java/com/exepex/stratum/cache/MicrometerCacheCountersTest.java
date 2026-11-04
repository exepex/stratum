package com.exepex.stratum.cache;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.exepex.stratum.cache.testsupport.ProductCatalog;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.cache.metrics.CacheMetricsRegistrar;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;

@DisplayName("MicrometerCacheCounters has Spring Boot measure each cache it does not measure yet")
class MicrometerCacheCountersTest {

  /** The bean name of Spring Boot's own cache manager, which its tag keeps unchanged. */
  private static final String BOOT_MANAGER = "cacheManager";

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  /** Spring Boot's registrar, mocked: a Spring Boot type at the module's boundary. */
  private final CacheMetricsRegistrar registrar = mock(CacheMetricsRegistrar.class);

  private final Cache cache = new ConcurrentMapCache(ProductCatalog.PRODUCTS);

  /** Returns counters whose registrar provider holds the mocked registrar. */
  private MicrometerCacheCounters counters() {
    var beanFactory = new DefaultListableBeanFactory();
    beanFactory.registerSingleton("cacheMetricsRegistrar", registrar);
    return new MicrometerCacheCounters(
        registry, beanFactory.getBeanProvider(CacheMetricsRegistrar.class));
  }

  @Test
  @DisplayName("a new cache is bound, tagged with the manager's name as Spring Boot shortens it")
  void bindsNewCache() {
    counters().bindCacheMetrics(cache, "redisCacheManager");

    verify(registrar).bindCacheToRegistry(cache, Tag.of(CacheConstants.TAG_CACHE_MANAGER, "redis"));
  }

  @Test
  @DisplayName("a cache Spring Boot measured at startup is not bound again")
  void skipsMeasuredCache() {
    FunctionCounter.builder(CacheConstants.METRIC_CACHE_GETS, cache, measuredCache -> 0)
        .tag(CacheConstants.TAG_CACHE, ProductCatalog.PRODUCTS)
        .tag(CacheConstants.TAG_CACHE_MANAGER, BOOT_MANAGER)
        .register(registry);

    counters().bindCacheMetrics(cache, BOOT_MANAGER);

    verifyNoInteractions(registrar);
  }
}
