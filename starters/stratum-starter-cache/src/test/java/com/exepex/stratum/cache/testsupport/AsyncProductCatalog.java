package com.exepex.stratum.cache.testsupport;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.cache.annotation.Cacheable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A service whose cached prices are returned as a {@code Mono}, a {@code Flux} or a {@code
 * CompletableFuture}, counting how often it really looks one up. It also has a blocking {@code
 * sync=true} lookup that waits until a test releases it, so concurrent misses overlap.
 */
public class AsyncProductCatalog {

  /** The name of the cache of prices returned as a {@code Mono} with {@code sync=true}. */
  public static final String MONO_PRODUCTS = "mono-products";

  /** The name of the cache of prices returned as a {@code Mono} without {@code sync}. */
  public static final String UNSYNCED_MONO_PRODUCTS = "unsynced-mono-products";

  /** The name of a {@code Mono} cache whose writes a test fails, so it warns on its own name. */
  public static final String MONO_WRITE_TEST_PRODUCTS = "mono-write-test-products";

  /** The name of the cache of products without a price, returned as an empty {@code Mono}. */
  public static final String MONO_UNPRICED_PRODUCTS = "mono-unpriced-products";

  /** The name of the cache of price lists returned as a {@code Flux} with {@code sync=true}. */
  public static final String FLUX_PRODUCTS = "flux-products";

  /** The name of the cache of prices returned as a {@code CompletableFuture}. */
  public static final String FUTURE_PRODUCTS = "future-products";

  /** The name of the cache of the blocking lookup that waits to be released. */
  public static final String SLOW_PRODUCTS = "slow-products";

  /** How many times a price was really looked up, rather than read from the cache. */
  private final AtomicInteger lookupCount = new AtomicInteger();

  /** Holds the slow lookups until a test releases them. */
  private final CountDownLatch slowLookupRelease = new CountDownLatch(1);

  /**
   * Looks up a product's price as a {@code Mono}, with {@code sync=true}.
   *
   * @param sku the product's stock-keeping unit
   * @return the price, as text
   */
  @Cacheable(cacheNames = MONO_PRODUCTS, sync = true)
  public Mono<String> monoPriceOf(String sku) {
    return Mono.fromSupplier(() -> lookedUp(sku));
  }

  /**
   * Looks up a product's price as a {@code Mono}, without {@code sync}.
   *
   * @param sku the product's stock-keeping unit
   * @return the price, as text
   */
  @Cacheable(UNSYNCED_MONO_PRODUCTS)
  public Mono<String> unsyncedMonoPriceOf(String sku) {
    return Mono.fromSupplier(() -> lookedUp(sku));
  }

  /**
   * Looks up a product's price as a {@code Mono}, with {@code sync=true}, cached under {@value
   * #MONO_WRITE_TEST_PRODUCTS}.
   *
   * @param sku the product's stock-keeping unit
   * @return the price, as text
   */
  @Cacheable(cacheNames = MONO_WRITE_TEST_PRODUCTS, sync = true)
  public Mono<String> monoWriteTestPriceOf(String sku) {
    return Mono.fromSupplier(() -> lookedUp(sku));
  }

  /**
   * Looks up a product that has no price, with {@code sync=true}.
   *
   * @param sku the product's stock-keeping unit
   * @return an empty {@code Mono}
   */
  @Cacheable(cacheNames = MONO_UNPRICED_PRODUCTS, sync = true)
  public Mono<String> monoUnpricedPriceOf(String sku) {
    return Mono.fromRunnable(lookupCount::incrementAndGet);
  }

  /**
   * Looks up a product's prices as a {@code Flux}, with {@code sync=true}.
   *
   * @param sku the product's stock-keeping unit
   * @return the list price and the sale price
   */
  @Cacheable(cacheNames = FLUX_PRODUCTS, sync = true)
  public Flux<String> fluxPricesOf(String sku) {
    return Flux.defer(() -> Flux.fromIterable(List.of(lookedUp(sku), "sale-" + sku)));
  }

  /**
   * Looks up a product's price as a {@code CompletableFuture}, with {@code sync=true}.
   *
   * @param sku the product's stock-keeping unit
   * @return the price, as text
   */
  @Cacheable(cacheNames = FUTURE_PRODUCTS, sync = true)
  public CompletableFuture<String> futurePriceOf(String sku) {
    return CompletableFuture.completedFuture(lookedUp(sku));
  }

  /**
   * Looks up a product's price with {@code sync=true}, after waiting until {@link
   * #releaseSlowLookups()} is called.
   *
   * @param sku the product's stock-keeping unit
   * @return the price, as text
   * @throws InterruptedException if the wait is interrupted
   */
  @Cacheable(cacheNames = SLOW_PRODUCTS, sync = true)
  public String slowPriceOf(String sku) throws InterruptedException {
    lookupCount.incrementAndGet();
    slowLookupRelease.await();
    return "price-of-" + sku;
  }

  /** Lets every waiting and later slow lookup finish. */
  public void releaseSlowLookups() {
    slowLookupRelease.countDown();
  }

  /**
   * Returns how many times a price was really looked up.
   *
   * @return the number of real lookups
   */
  public int lookups() {
    return lookupCount.get();
  }

  /** Counts a real lookup and returns the price. */
  private String lookedUp(String sku) {
    lookupCount.incrementAndGet();
    return "price-of-" + sku;
  }
}
