package com.exepex.stratum.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.cache.testsupport.ProductCatalog;
import com.exepex.stratum.cache.testsupport.RecordingCacheErrorHandler;
import com.exepex.stratum.cache.testsupport.StubCache;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("StratumCache serves future and reactive methods with the loader run at most once")
class StratumCacheRetrieveTest {

  /** The key the tests use. */
  private static final String KEY = "sku-1";

  /** The value the loaders return. */
  private static final String VALUE = "price-of-sku-1";

  private final RecordingCacheErrorHandler handler = new RecordingCacheErrorHandler();

  @Nested
  @DisplayName("when the write after a load fails")
  class WriteAfterLoadFails {

    /** A cache whose write after a load fails, as a paused Redis does on a SET. */
    private final StubCache failingWrites = new StubCache(ProductCatalog.PRODUCTS, false, true);

    @Test
    @DisplayName(
        "the loaded value is returned, the loader ran once and the failure was reported"
            + " once as a put")
    void returnsLoadedValue() {
      var cache = new StratumCache(failingWrites, handler, false);

      var value = cache.retrieve(KEY, () -> CompletableFuture.completedFuture(VALUE)).join();

      assertThat(value).isEqualTo(VALUE);
      assertThat(failingWrites.loads()).isOne();
      assertThat(handler.calls()).containsExactly("put:products");
    }

    @Test
    @DisplayName("a handler that rethrows fails the call with the write's own error")
    void handlerRethrowFailsCall() {
      var rethrowing = new StratumCache(failingWrites, new RecordingCacheErrorHandler(true), false);

      var retrieved = rethrowing.retrieve(KEY, () -> CompletableFuture.completedFuture(VALUE));

      assertFailsWith(retrieved, StubCache.FAILURE);
      assertThat(failingWrites.loads()).isOne();
    }
  }

  @Nested
  @DisplayName("with a null result")
  class NullResult {

    /** A cache whose write fails, as Redis refuses to store a loaded null. */
    private final StubCache refusing = new StubCache(ProductCatalog.PRODUCTS, false, true);

    @Test
    @DisplayName(
        "a cache that refuses nulls gives null, the loader ran once and nothing is reported")
    void refusedNullIsNotReported() {
      var cache = new StratumCache(refusing, handler, true);

      var value = cache.retrieve(KEY, () -> CompletableFuture.completedFuture(null)).join();

      assertThat(value).isNull();
      assertThat(refusing.loads()).isOne();
      assertThat(handler.total()).isZero();
    }

    @Test
    @DisplayName("in a cache that stores nulls, a failed write after a null load is reported once")
    void failedNullWriteIsReportedWhereNullsAreStored() {
      var cache = new StratumCache(refusing, handler, false);

      var value = cache.retrieve(KEY, () -> CompletableFuture.completedFuture(null)).join();

      assertThat(value).isNull();
      assertThat(handler.calls()).containsExactly("put:products");
    }
  }

  @Nested
  @DisplayName("when the call fails before or inside the load")
  class FailureBeforeOrInsideLoad {

    @Test
    @DisplayName("a failed read is passed on, the loader never ran and the handler is untouched")
    void passesReadFailureOn() {
      var cache =
          new StratumCache(new StubCache(ProductCatalog.PRODUCTS, true, false), handler, true);
      var loads = new AtomicInteger();

      var retrieved = cache.retrieve(KEY, counted(loads));

      assertFailsWith(retrieved, StubCache.FAILURE);
      assertThat(loads).hasValue(0);
      assertThat(handler.total()).isZero();
    }

    @Test
    @DisplayName("the method's own error is passed on and the handler is untouched")
    void passesLoaderFailureOn() {
      var cache = new StratumCache(new StubCache(ProductCatalog.PRODUCTS), handler, true);
      var methodError = new IllegalStateException("no price");

      var retrieved = cache.retrieve(KEY, () -> CompletableFuture.failedFuture(methodError));

      assertFailsWith(retrieved, methodError);
      assertThat(handler.total()).isZero();
    }
  }

  /** Returns a loader that counts its runs and returns the value. */
  private Supplier<CompletableFuture<String>> counted(AtomicInteger loads) {
    return () -> CompletableFuture.completedFuture(VALUE + loads.incrementAndGet());
  }

  /** Checks that a call failed with the given error, as the caller's future reports it. */
  private void assertFailsWith(CompletableFuture<?> retrieved, Throwable expected) {
    assertThat(retrieved)
        .failsWithin(Duration.ZERO)
        .withThrowableThat()
        .havingRootCause()
        .isSameAs(expected);
  }
}
