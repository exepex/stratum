package com.exepex.stratum.cache.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.cache.CacheCounters;
import com.exepex.stratum.cache.testsupport.ProductCatalog;
import com.exepex.stratum.cache.testsupport.RecordingCacheCounters;
import com.exepex.stratum.cache.testsupport.RecordingCacheErrorHandler;
import com.exepex.stratum.cache.testsupport.RecordingCacheManager;
import com.exepex.stratum.cache.testsupport.StubCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;

@DisplayName(
    "Over the real interceptor, each cache error reaches the error handler exactly once"
        + " and the loader runs at most once")
class CacheLayerErrorPathTest {

  /** The sku the tests cache. */
  private static final String SKU = "sku-1";

  /** Returns a context with the cache layer over the given cache manager. */
  private static ApplicationContextRunner runnerWith(CacheManager manager) {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                CacheAutoConfiguration.class,
                StratumCacheAutoConfiguration.class,
                CacheLayerAutoConfiguration.class))
        .withBean(ProductCatalog.class)
        .withBean(CacheManager.class, () -> manager)
        .withBean(CacheCounters.class, RecordingCacheCounters::new);
  }

  /** Returns a configurer that sets only the given error handler. */
  private static CachingConfigurer configurerWith(CacheErrorHandler handler) {
    return new CachingConfigurer() {
      @Override
      public CacheErrorHandler errorHandler() {
        return handler;
      }
    };
  }

  @Nested
  @DisplayName("with reads failing and no service handler (the fail-open)")
  class FailOpen {

    @Test
    @DisplayName("a sync=false call runs the method and returns its value")
    void syncFalseKeepsWorking() {
      runnerWith(new RecordingCacheManager(true, false))
          .run(
              context -> {
                var catalog = context.getBean(ProductCatalog.class);

                assertThat(catalog.priceOf(SKU)).isEqualTo("price-of-" + SKU);
                assertThat(catalog.priceOf(SKU)).isEqualTo("price-of-" + SKU);
                assertThat(catalog.lookups()).isEqualTo(2);
                assertThat(
                        context
                            .getBean(RecordingCacheCounters.class)
                            .failures(ProductCatalog.PRODUCTS, "get"))
                    .isEqualTo(2);
              });
    }

    @Test
    @DisplayName("a sync=true call runs the method once and returns its value")
    void syncTrueKeepsWorking() {
      runnerWith(new RecordingCacheManager(true, false))
          .run(
              context -> {
                var catalog = context.getBean(ProductCatalog.class);

                assertThat(catalog.syncedPriceOf(SKU)).isEqualTo("synced-price-of-" + SKU);
                assertThat(catalog.syncedLookups()).isOne();
                assertThat(
                        context
                            .getBean(RecordingCacheCounters.class)
                            .failures(ProductCatalog.SYNCED_PRODUCTS, "get"))
                    .isOne();
              });
    }
  }

  @Nested
  @DisplayName("with the write after a load failing and a service handler")
  class OneErrorPath {

    @Test
    @DisplayName("sync=false, handler swallows: one handler call, one load, the value returned")
    void syncFalseSwallowed() {
      var serviceHandler = new RecordingCacheErrorHandler();
      runnerWith(new RecordingCacheManager(false, true))
          .withBean(CachingConfigurer.class, () -> configurerWith(serviceHandler))
          .run(
              context -> {
                var catalog = context.getBean(ProductCatalog.class);

                assertThat(catalog.priceOf(SKU)).isEqualTo("price-of-" + SKU);
                assertThat(catalog.lookups()).isOne();
                assertThat(serviceHandler.calls())
                    .containsExactly("put:" + ProductCatalog.PRODUCTS);
              });
    }

    @Test
    @DisplayName(
        "sync=false, handler rethrows: one handler call, one load, the error reaches the"
            + " caller unchanged")
    void syncFalseRethrown() {
      var serviceHandler = new RecordingCacheErrorHandler(true);
      runnerWith(new RecordingCacheManager(false, true))
          .withBean(CachingConfigurer.class, () -> configurerWith(serviceHandler))
          .run(
              context -> {
                var catalog = context.getBean(ProductCatalog.class);

                assertThatThrownBy(() -> catalog.priceOf(SKU)).isSameAs(StubCache.FAILURE);
                assertThat(catalog.lookups()).isOne();
                assertThat(serviceHandler.calls())
                    .containsExactly("put:" + ProductCatalog.PRODUCTS);
              });
    }

    @Test
    @DisplayName("sync=true, handler swallows: one handler call, one load, the value returned")
    void syncTrueSwallowed() {
      var serviceHandler = new RecordingCacheErrorHandler();
      runnerWith(new RecordingCacheManager(false, true))
          .withBean(CachingConfigurer.class, () -> configurerWith(serviceHandler))
          .run(
              context -> {
                var catalog = context.getBean(ProductCatalog.class);

                assertThat(catalog.syncedPriceOf(SKU)).isEqualTo("synced-price-of-" + SKU);
                assertThat(catalog.syncedLookups()).isOne();
                assertThat(serviceHandler.calls())
                    .containsExactly("put:" + ProductCatalog.SYNCED_PRODUCTS);
              });
    }

    @Test
    @DisplayName(
        "sync=true, handler rethrows: one handler call, one load, the error reaches the"
            + " caller unchanged")
    void syncTrueRethrown() {
      var serviceHandler = new RecordingCacheErrorHandler(true);
      runnerWith(new RecordingCacheManager(false, true))
          .withBean(CachingConfigurer.class, () -> configurerWith(serviceHandler))
          .run(
              context -> {
                var catalog = context.getBean(ProductCatalog.class);

                assertThatThrownBy(() -> catalog.syncedPriceOf(SKU)).isSameAs(StubCache.FAILURE);
                assertThat(catalog.syncedLookups()).isOne();
                assertThat(serviceHandler.calls())
                    .containsExactly("put:" + ProductCatalog.SYNCED_PRODUCTS);
              });
    }

    @Test
    @DisplayName("the method's own error reaches the caller unchanged, on a healthy cache")
    void methodErrorReachesCallerUnchanged() {
      runnerWith(new RecordingCacheManager())
          .run(
              context -> {
                var catalog = context.getBean(ProductCatalog.class);

                assertThatThrownBy(() -> catalog.missingPriceOf(SKU))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("unknown sku " + SKU);
              });
    }
  }
}
