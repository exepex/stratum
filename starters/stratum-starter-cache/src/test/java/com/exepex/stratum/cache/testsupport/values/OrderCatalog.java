package com.exepex.stratum.cache.testsupport.values;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.cache.annotation.Cacheable;

/** A service whose orders are cached in Redis as JSON, counting how often it loads one. */
public class OrderCatalog {

  /** The cache of orders. */
  public static final String ORDERS = "orders";

  /** The cache of orders loaded with {@code sync=true}. */
  public static final String SYNCED_ORDERS = "synced-orders";

  /** The cache of each order's times. */
  public static final String ORDER_TIMES = "order-times";

  /** The cache of orders that may be missing. */
  public static final String OPTIONAL_ORDERS = "optional-orders";

  /** The cache of orders loaded in the background. */
  public static final String FUTURE_ORDERS = "future-orders";

  /** The cache of a method that returns {@code Object}, which is never stored. */
  public static final String UNTYPED_ORDERS = "untyped-orders";

  /** The cache of a method that names its cache manager, so Stratum's layer is skipped. */
  public static final String NAMED_MANAGER_ORDERS = "named-manager-orders";

  /** The times every order has. */
  public static final List<Instant> TIMES =
      List.of(Instant.parse("2026-11-05T08:00:00Z"), Instant.parse("2026-11-05T09:30:00Z"));

  /** How many times an order was really loaded, rather than read from the cache. */
  private final AtomicInteger loadCount = new AtomicInteger();

  /**
   * Loads an order, or returns it from the cache.
   *
   * @param id the order's identifier
   * @return the order
   */
  @Cacheable(ORDERS)
  public Order orderOf(String id) {
    return load(id);
  }

  /**
   * Loads an order with {@code sync=true}, or returns it from the cache.
   *
   * @param id the order's identifier
   * @return the order
   */
  @Cacheable(cacheNames = SYNCED_ORDERS, sync = true)
  public Order syncedOrderOf(String id) {
    return load(id);
  }

  /**
   * Loads an order's times, or returns them from the cache.
   *
   * @param id the order's identifier
   * @return the times
   */
  @Cacheable(ORDER_TIMES)
  public List<Instant> timesOf(String id) {
    loadCount.incrementAndGet();
    return TIMES;
  }

  /**
   * Finds an order, or returns it from the cache.
   *
   * @param id the order's identifier
   * @return the order
   */
  @Cacheable(OPTIONAL_ORDERS)
  public Optional<Order> findOrder(String id) {
    return Optional.of(load(id));
  }

  /**
   * Loads an order in the background, or returns it from the cache.
   *
   * @param id the order's identifier
   * @return the order, once loaded
   */
  @Cacheable(FUTURE_ORDERS)
  public CompletableFuture<Order> futureOrderOf(String id) {
    return CompletableFuture.completedFuture(load(id));
  }

  /**
   * Loads an order declared as {@code Object}, which Stratum refuses to store.
   *
   * @param id the order's identifier
   * @return the order
   */
  @Cacheable(UNTYPED_ORDERS)
  public Object untypedOrderOf(String id) {
    return load(id);
  }

  /**
   * Loads an order through a cache manager named on the annotation, which skips Stratum's layer.
   *
   * @param id the order's identifier
   * @return the order
   */
  @Cacheable(cacheNames = NAMED_MANAGER_ORDERS, cacheManager = "cacheManager")
  public Order namedManagerOrderOf(String id) {
    return load(id);
  }

  /**
   * Returns how many times an order was really loaded.
   *
   * @return the number of loads
   */
  public int loads() {
    return loadCount.get();
  }

  /** Counts a load and returns the order. */
  private Order load(String id) {
    loadCount.incrementAndGet();
    return new Order(id, CachedValuesTestData.ORDER.quantity());
  }
}
