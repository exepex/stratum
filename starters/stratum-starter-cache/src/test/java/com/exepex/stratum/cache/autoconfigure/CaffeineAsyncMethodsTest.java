package com.exepex.stratum.cache.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.cache.testsupport.AsyncProductCatalog;
import com.exepex.stratum.cache.testsupport.CaffeineLayerContexts;
import com.exepex.stratum.cache.testsupport.RecordingCacheCounters;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("Caffeine caches serve methods that return a Mono, a Flux or a CompletableFuture")
class CaffeineAsyncMethodsTest {

  /** The key the tests use. */
  private static final String SKU = "sku-1";

  /** Spring Boot's Caffeine caches with Stratum's defaults and layer, and the async catalog. */
  private final ApplicationContextRunner contextRunner =
      CaffeineLayerContexts.caffeineWithLayer().withBean(AsyncProductCatalog.class);

  @Nested
  @DisplayName("a method with a value")
  class WithValue {

    /** Each kind of method, called and waited for, with the value it returns. */
    private static final List<Named<Function<AsyncProductCatalog, Object>>> CACHED_CALLS =
        List.of(
            Named.of("Mono, sync", catalog -> catalog.monoPriceOf(SKU).block()),
            Named.of("Mono, no sync", catalog -> catalog.unsyncedMonoPriceOf(SKU).block()),
            Named.of("Flux, sync", catalog -> catalog.fluxPricesOf(SKU).collectList().block()),
            Named.of("CompletableFuture, sync", catalog -> catalog.futurePriceOf(SKU).join()));

    @ParameterizedTest(name = "{0}")
    @FieldSource("CACHED_CALLS")
    @DisplayName("is read from the cache on the second call, with no failure counted")
    void cachedOnSecondCall(Function<AsyncProductCatalog, Object> call) {
      contextRunner.run(context -> assertCachedOnSecondCall(context, call));
    }

    /** Calls the method twice and checks it ran once, gave the same value and nothing failed. */
    private void assertCachedOnSecondCall(
        AssertableApplicationContext context, Function<AsyncProductCatalog, Object> call) {
      var catalog = context.getBean(AsyncProductCatalog.class);

      var first = call.apply(catalog);
      var second = call.apply(catalog);

      assertThat(second).isEqualTo(first).isNotNull();
      assertThat(catalog.lookups()).isOne();
      assertThat(context.getBean(RecordingCacheCounters.class).failureSeries()).isZero();
    }
  }

  @Test
  @DisplayName("an empty Mono is not stored: the method runs on each call and nothing fails")
  void emptyMonoRunsEachTime() {
    contextRunner.run(context -> assertEmptyMonoRunsEachTime(context));
  }

  /** Calls the method of a product without a price twice and checks it ran each time. */
  private void assertEmptyMonoRunsEachTime(AssertableApplicationContext context) {
    var catalog = context.getBean(AsyncProductCatalog.class);

    assertThat(catalog.monoUnpricedPriceOf(SKU).block()).isNull();
    assertThat(catalog.monoUnpricedPriceOf(SKU).block()).isNull();

    assertThat(catalog.lookups()).isEqualTo(2);
    assertThat(context.getBean(RecordingCacheCounters.class).failureSeries()).isZero();
  }

  @Nested
  @DisplayName("concurrent misses of one key with sync=true")
  class Stampede {

    /** How many callers miss the same key at once. */
    private static final int CALLERS = 100;

    /** How long the callers may take to reach the load or start waiting for it. */
    private static final Duration CALLERS_WAIT = Duration.ofSeconds(10);

    @Test
    @DisplayName("run a blocking method once per instance; every caller gets its value")
    void loadOnce() {
      contextRunner.run(context -> assertLoadedOnce(context));
    }

    /**
     * Starts the callers, waits until each is either inside the method or waiting for it, then lets
     * the method finish and checks it ran once.
     */
    private void assertLoadedOnce(AssertableApplicationContext context) throws Exception {
      var catalog = context.getBean(AsyncProductCatalog.class);
      var callers = new CopyOnWriteArrayList<Thread>();
      try (var executor =
          Executors.newFixedThreadPool(CALLERS, task -> recorded(callers, new Thread(task)))) {
        List<Future<String>> prices =
            IntStream.range(0, CALLERS)
                .mapToObj(caller -> executor.submit(() -> catalog.slowPriceOf(SKU)))
                .toList();
        await()
            .atMost(CALLERS_WAIT)
            .until(() -> callers.size() == CALLERS && callers.stream().allMatch(this::isWaiting));
        catalog.releaseSlowLookups();

        // A loop rather than a stream: Future.get throws checked exceptions.
        for (var price : prices) {
          assertThat(price.get()).isEqualTo("price-of-" + SKU);
        }
      }
      assertThat(catalog.lookups()).isOne();
    }

    /** Records a caller's thread, so the test can see when it waits, and returns it. */
    private Thread recorded(List<Thread> callers, Thread caller) {
      callers.add(caller);
      return caller;
    }

    /** Returns whether a caller waits: for the release, or for the one load of its key. */
    private boolean isWaiting(Thread caller) {
      return caller.getState() == Thread.State.WAITING || caller.getState() == Thread.State.BLOCKED;
    }
  }
}
