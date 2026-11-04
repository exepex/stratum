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

  /** How many times a price was really looked up, rather than read from the cache. */
  private final AtomicInteger lookupCount = new AtomicInteger();

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
}
