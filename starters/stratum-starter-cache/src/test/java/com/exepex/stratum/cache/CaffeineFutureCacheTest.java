package com.exepex.stratum.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.cache.testsupport.ProductCatalog;
import com.exepex.stratum.cache.testsupport.StubCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCache;

@DisplayName("CaffeineFutureCache serves future and reactive methods from a Caffeine cache")
class CaffeineFutureCacheTest {

  /** The key the tests use. */
  private static final String KEY = "sku-1";

  /** The value the tests cache. */
  private static final String VALUE = "price-of-sku-1";

  /** A Caffeine cache without async mode that refuses nulls, as Stratum's default sets it. */
  private final CaffeineCache caffeine =
      new CaffeineCache(ProductCatalog.PRODUCTS, Caffeine.newBuilder().build(), false);

  private final CaffeineFutureCache cache = new CaffeineFutureCache(caffeine);

  @Nested
  @DisplayName("which caches it serves")
  class WhichCaches {

    private final CaffeineFutures futures = new CaffeineFutures();

    @Test
    @DisplayName("a Caffeine cache without async mode is served by it")
    void servesCacheWithoutAsyncMode() {
      assertThat(futures.servingFutures(caffeine)).isInstanceOf(CaffeineFutureCache.class);
    }

    @Test
    @DisplayName("a Caffeine cache in async mode keeps Caffeine's own answers")
    void leavesAsyncModeCache() {
      var async =
          new CaffeineCache(ProductCatalog.PRODUCTS, Caffeine.newBuilder().buildAsync(), false);

      assertThat(futures.servingFutures(async)).isSameAs(async);
    }

    @Test
    @DisplayName("any other cache is left as it is")
    void leavesOtherCache() {
      var other = new StubCache(ProductCatalog.PRODUCTS);

      assertThat(futures.servingFutures(other)).isSameAs(other);
    }
  }

  @Nested
  @DisplayName("a read without a loader")
  class Read {

    @Test
    @DisplayName("is null on a miss")
    void nullOnMiss() {
      assertThat(cache.retrieve(KEY)).isNull();
    }

    @Test
    @DisplayName("is the cached entry on a hit")
    void entryOnHit() {
      caffeine.put(KEY, VALUE);

      assertThat(cache.retrieve(KEY))
          .succeedsWithin(Duration.ZERO)
          .isInstanceOfSatisfying(
              Cache.ValueWrapper.class, entry -> assertThat(entry.get()).isEqualTo(VALUE));
    }
  }

  @Nested
  @DisplayName("a read with a loader")
  class ReadWithLoader {

    private final AtomicInteger loads = new AtomicInteger();

    @Test
    @DisplayName("on a hit, returns the cached value without loading")
    void hitDoesNotLoad() {
      caffeine.put(KEY, VALUE);

      assertThat(cache.retrieve(KEY, this::load).join()).isEqualTo(VALUE);
      assertThat(loads).hasValue(0);
    }

    @Test
    @DisplayName("on a miss, loads once and stores the value for the next read")
    void missLoadsAndStores() {
      assertThat(cache.retrieve(KEY, this::load).join()).isEqualTo(VALUE);
      assertThat(cache.retrieve(KEY, this::load).join()).isEqualTo(VALUE);

      assertThat(loads).hasValue(1);
      assertThat(caffeine.get(KEY, String.class)).isEqualTo(VALUE);
    }

    @Test
    @DisplayName("a loaded null the cache refuses fails the call and stores nothing")
    void refusedNullFails() {
      var retrieved = cache.retrieve(KEY, () -> CompletableFuture.completedFuture(null));

      assertThat(retrieved)
          .failsWithin(Duration.ZERO)
          .withThrowableThat()
          .havingRootCause()
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(caffeine.get(KEY)).isNull();
    }

    /** Counts a load and returns the value. */
    private CompletableFuture<String> load() {
      loads.incrementAndGet();
      return CompletableFuture.completedFuture(VALUE);
    }
  }

  @Test
  @DisplayName("every other call is forwarded to the Caffeine cache")
  void forwardsEveryOtherCall() {
    var delegate = new StubCache(ProductCatalog.PRODUCTS);
    var forwarding = new CaffeineFutureCache(delegate);

    forwarding.getName();
    forwarding.getNativeCache();
    forwarding.get(KEY);
    forwarding.get(KEY, String.class);
    forwarding.get(KEY, () -> VALUE);
    forwarding.put(KEY, VALUE);
    forwarding.putIfAbsent(KEY, VALUE);
    forwarding.evict(KEY);
    forwarding.evictIfPresent(KEY);
    forwarding.clear();
    forwarding.invalidate();

    assertThat(delegate.calls())
        .containsExactly(
            "getName",
            "getNativeCache",
            "get",
            "getTyped",
            "getWithLoader",
            "put",
            "putIfAbsent",
            "evict",
            "evictIfPresent",
            "clear",
            "invalidate");
  }
}
