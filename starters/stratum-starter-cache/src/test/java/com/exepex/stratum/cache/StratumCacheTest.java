package com.exepex.stratum.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.cache.testsupport.RecordingCacheErrorHandler;
import com.exepex.stratum.cache.testsupport.StubCache;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;

@DisplayName("StratumCache forwards every call and runs the loader at most once")
class StratumCacheTest {

  /** The key the tests use. */
  private static final String KEY = "sku-1";

  /** The value the loaders return. */
  private static final String VALUE = "price-of-sku-1";

  private final RecordingCacheErrorHandler handler = new RecordingCacheErrorHandler();

  @Nested
  @DisplayName("when the write after a load fails")
  class WriteAfterLoadFails {

    /** A cache whose write after a load fails, as a paused Redis does on a SET. */
    private final StubCache failingWrites = new StubCache("products", false, true);

    private final StratumCache cache = new StratumCache(failingWrites, handler);

    @Test
    @DisplayName(
        "the loaded value is returned, the loader ran once and the failure was reported"
            + " once as a put")
    void returnsLoadedValue() {
      var value = cache.get(KEY, () -> VALUE);

      assertThat(value).isEqualTo(VALUE);
      assertThat(failingWrites.loads()).isOne();
      assertThat(handler.calls()).containsExactly("put:products");
    }

    @Test
    @DisplayName("a handler that rethrows gives the caller its error, wrapped for Spring to unwrap")
    void handlerRethrowReachesCaller() {
      var rethrowing = new StratumCache(failingWrites, new RecordingCacheErrorHandler(true));

      assertThatThrownBy(() -> rethrowing.get(KEY, () -> VALUE))
          .isInstanceOfSatisfying(
              Cache.ValueRetrievalException.class,
              failure -> assertThat(failure).hasCause(StubCache.FAILURE));
      assertThat(failingWrites.loads()).isOne();
    }
  }

  @Nested
  @DisplayName("when the read before a load fails")
  class ReadFails {

    @Test
    @DisplayName(
        "the failure is rethrown for Spring to report, the loader never ran and the"
            + " handler is untouched")
    void rethrowsBeforeLoad() {
      var failingReads = new StubCache("products", true, false);
      var cache = new StratumCache(failingReads, handler);
      var loads = new AtomicInteger();

      assertThatThrownBy(
              () ->
                  cache.get(
                      KEY,
                      () -> {
                        loads.incrementAndGet();
                        return VALUE;
                      }))
          .isSameAs(StubCache.FAILURE);
      assertThat(loads).hasValue(0);
      assertThat(handler.total()).isZero();
    }
  }

  @Nested
  @DisplayName("when the loader itself fails")
  class LoaderFails {

    @Test
    @DisplayName("the method's own error is rethrown unchanged and the handler is untouched")
    void rethrowsLoaderError() {
      var healthy = new StubCache("products");
      var cache = new StratumCache(healthy, handler);
      var methodError = new IllegalStateException("no price");

      assertThatThrownBy(
              () ->
                  cache.get(
                      KEY,
                      () -> {
                        throw methodError;
                      }))
          .isInstanceOfSatisfying(
              Cache.ValueRetrievalException.class,
              failure -> assertThat(failure).hasCause(methodError));
      assertThat(handler.total()).isZero();
    }
  }

  @Nested
  @DisplayName("on every other call")
  class Forwarding {

    @Test
    @DisplayName("each Cache method is forwarded to the wrapped cache")
    void forwardsEveryMethod() {
      var delegate = new StubCache("products");
      var cache = new StratumCache(delegate, handler);

      cache.getName();
      cache.getNativeCache();
      cache.get(KEY);
      cache.get(KEY, String.class);
      cache.retrieve(KEY);
      cache.retrieve(KEY, () -> java.util.concurrent.CompletableFuture.completedFuture(VALUE));
      cache.put(KEY, VALUE);
      cache.putIfAbsent(KEY, VALUE);
      cache.evict(KEY);
      cache.evictIfPresent(KEY);
      cache.clear();
      cache.invalidate();

      assertThat(delegate.calls())
          .containsExactly(
              "getName",
              "getNativeCache",
              "get",
              "getTyped",
              "retrieve",
              "retrieveWithLoader",
              "put",
              "putIfAbsent",
              "evict",
              "evictIfPresent",
              "clear",
              "invalidate");
    }
  }
}
