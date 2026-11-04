package com.exepex.stratum.cache.testsupport;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;

/**
 * A service whose prices are cached under {@value #PRODUCTS}, counting how often it looks one up.
 */
public class ProductCatalog {

  /** The name of the cache that holds the prices. */
  public static final String PRODUCTS = "products";

  /** The name of the cache that holds the prices looked up with {@code sync=true}. */
  public static final String SYNCED_PRODUCTS = "synced-products";

  /**
   * The name of a second {@code sync=true} cache, so a test that fails only its writes warns on its
   * own name and leaves the warning of {@value #SYNCED_PRODUCTS} to the other tests.
   */
  public static final String WRITE_TEST_PRODUCTS = "write-test-products";

  /** The name of the cache whose loads fail, for a method's own error. */
  public static final String MISSING_PRODUCTS = "missing-products";

  /** How many times a price was really looked up, rather than read from the cache. */
  private final AtomicInteger lookupCount = new AtomicInteger();

  /** How many times a synced price was really looked up. */
  private final AtomicInteger syncedLookupCount = new AtomicInteger();

  /**
   * Looks up a product's price, or returns it from the cache.
   *
   * @param sku the product's stock-keeping unit
   * @return the price, as text
   */
  @Cacheable(PRODUCTS)
  public String priceOf(String sku) {
    lookupCount.incrementAndGet();
    return "price-of-" + sku;
  }

  /**
   * Looks up a product's price with {@code sync=true}, so one load serves concurrent callers.
   *
   * @param sku the product's stock-keeping unit
   * @return the price, as text
   */
  @Cacheable(cacheNames = SYNCED_PRODUCTS, sync = true)
  public String syncedPriceOf(String sku) {
    syncedLookupCount.incrementAndGet();
    return "synced-price-of-" + sku;
  }

  /**
   * Looks up a product's price with {@code sync=true}, cached under {@value #WRITE_TEST_PRODUCTS};
   * counted with the synced lookups.
   *
   * @param sku the product's stock-keeping unit
   * @return the price, as text
   */
  @Cacheable(cacheNames = WRITE_TEST_PRODUCTS, sync = true)
  public String writeTestPriceOf(String sku) {
    syncedLookupCount.incrementAndGet();
    return "synced-price-of-" + sku;
  }

  /**
   * Fails as a method whose own error must reach the caller unchanged, cache or no cache.
   *
   * @param sku the product's stock-keeping unit
   * @return never
   */
  @Cacheable(cacheNames = MISSING_PRODUCTS, sync = true)
  public String missingPriceOf(String sku) {
    throw new IllegalStateException("unknown sku " + sku);
  }

  /** Removes every price from the cache. */
  @CacheEvict(cacheNames = PRODUCTS, allEntries = true)
  public void forgetAllPrices() {
    // The annotation does the work.
  }

  /**
   * Returns how many times a price was really looked up.
   *
   * @return the number of lookups
   */
  public int lookups() {
    return lookupCount.get();
  }

  /**
   * Returns how many times a synced price was really looked up.
   *
   * @return the number of synced lookups
   */
  public int syncedLookups() {
    return syncedLookupCount.get();
  }
}
