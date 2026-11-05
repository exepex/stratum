package com.exepex.stratum.cache.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.cache.testsupport.ProductCatalog;
import com.exepex.stratum.cache.testsupport.RecordingCacheCounters;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.caffeine.CaffeineCacheManager;

@DisplayName("A cached method's null result is not stored in Caffeine and is not a failure")
class CaffeineNullResultsTest {

  /** The key the tests use. */
  private static final String SKU = "sku-1";

  /** Spring Boot's Caffeine caches with Stratum's defaults and cache layer. */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withPropertyValues("spring.cache.type=caffeine")
          .withConfiguration(
              AutoConfigurations.of(
                  CacheAutoConfiguration.class,
                  StratumCacheAutoConfiguration.class,
                  CaffeineCacheAutoConfiguration.class,
                  CacheLayerAutoConfiguration.class))
          .withBean(RecordingCacheCounters.class, RecordingCacheCounters::new)
          .withBean(ProductCatalog.class);

  @Test
  @DisplayName("without sync, the method runs on each call and no failure is counted")
  void nullWithoutSync() {
    assertRunsEachTime(ProductCatalog.UNPRICED_PRODUCTS, catalog -> catalog.unpricedPriceOf(SKU));
  }

  @Test
  @DisplayName("with sync, the method runs on each call and no failure is counted")
  void nullWithSync() {
    assertRunsEachTime(
        ProductCatalog.SYNCED_UNPRICED_PRODUCTS, catalog -> catalog.syncedUnpricedPriceOf(SKU));
  }

  @Test
  @DisplayName("a @CachePut that returns null removes the cached price, so the method runs again")
  void nullPutRemovesEntry() {
    contextRunner.run(context -> assertWithdrawnPriceLookedUpAgain(context));
  }

  /** Caches a price, withdraws it with a null put, and checks the next call runs the method. */
  private void assertWithdrawnPriceLookedUpAgain(AssertableApplicationContext context) {
    var catalog = context.getBean(ProductCatalog.class);
    catalog.priceOf(SKU);

    catalog.withdrawPrice(SKU);
    catalog.priceOf(SKU);

    assertThat(catalog.lookups()).isEqualTo(2);
    assertThat(context.getBean(RecordingCacheCounters.class).failureSeries()).isZero();
  }

  @Test
  @DisplayName("with cache-null-values=true, a null result is stored, so the method runs once")
  void storesNullsWhenAsked() {
    contextRunner
        .withPropertyValues(CacheConstants.CAFFEINE_CACHE_NULL_VALUES + "=true")
        .run(context -> assertNullStored(context));
  }

  /** Looks up a product without a price twice and checks the method ran once. */
  private void assertNullStored(AssertableApplicationContext context) {
    var catalog = context.getBean(ProductCatalog.class);
    catalog.unpricedPriceOf(SKU);
    catalog.unpricedPriceOf(SKU);

    assertThat(catalog.unpricedLookups()).isOne();
  }

  /** Calls a lookup of a product without a price twice and checks it ran each time. */
  private void assertRunsEachTime(String cacheName, Consumer<ProductCatalog> lookup) {
    contextRunner.run(context -> assertRunsEachTime(context, cacheName, lookup));
  }

  /** Runs the lookup twice in a started context and checks nothing was stored or counted. */
  private void assertRunsEachTime(
      AssertableApplicationContext context, String cacheName, Consumer<ProductCatalog> lookup) {
    var catalog = context.getBean(ProductCatalog.class);
    lookup.accept(catalog);
    lookup.accept(catalog);

    assertThat(catalog.unpricedLookups()).isEqualTo(2);
    assertThat(context.getBean(CaffeineCacheManager.class).getCache(cacheName).get(SKU)).isNull();
    assertThat(context.getBean(RecordingCacheCounters.class).failureSeries()).isZero();
  }
}
