package com.exepex.stratum.cache.testsupport;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;

/**
 * A cache whose reads, writes or the write after a load can be made to fail, as during a Redis
 * outage, and that records the calls it gets. A loader error is wrapped in a {@link
 * ValueRetrievalException}, as Spring's own caches wrap it.
 */
public class StubCache implements Cache {

  /** The failure every failing call throws. */
  public static final RuntimeException FAILURE = new RuntimeException("stub cache failure");

  /** The cache's name. */
  private final String name;

  /** Whether reads fail before any load. */
  private final boolean failReads;

  /** Whether writes fail, and with them the write after a load. */
  private final boolean failWrites;

  /** The methods called, in order. */
  private final List<String> calls = new CopyOnWriteArrayList<>();

  /** How many times a loader ran. */
  private final AtomicInteger loads = new AtomicInteger();

  /**
   * Creates a healthy cache.
   *
   * @param name the cache's name
   */
  public StubCache(String name) {
    this(name, false, false);
  }

  /**
   * Creates a cache that may fail.
   *
   * @param name the cache's name
   * @param failReads whether reads fail before any load
   * @param failWrites whether writes fail, and the write after a load with them
   */
  public StubCache(String name, boolean failReads, boolean failWrites) {
    this.name = name;
    this.failReads = failReads;
    this.failWrites = failWrites;
  }

  @Override
  public String getName() {
    calls.add("getName");
    return name;
  }

  @Override
  public Object getNativeCache() {
    calls.add("getNativeCache");
    return this;
  }

  @Override
  public @Nullable ValueWrapper get(Object key) {
    calls.add("get");
    if (failReads) {
      throw FAILURE;
    }
    return null;
  }

  @Override
  public <T> @Nullable T get(Object key, Class<T> type) {
    calls.add("getTyped");
    if (failReads) {
      throw FAILURE;
    }
    return null;
  }

  @Override
  public <T> T get(Object key, Callable<T> valueLoader) {
    calls.add("getWithLoader");
    if (failReads) {
      throw FAILURE;
    }
    T value;
    try {
      loads.incrementAndGet();
      value = valueLoader.call();
    } catch (Exception failure) {
      throw new ValueRetrievalException(key, valueLoader, failure);
    }
    if (failWrites) {
      throw FAILURE;
    }
    return value;
  }

  @Override
  public CompletableFuture<?> retrieve(Object key) {
    calls.add("retrieve");
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public <T> CompletableFuture<T> retrieve(Object key, Supplier<CompletableFuture<T>> valueLoader) {
    calls.add("retrieveWithLoader");
    return valueLoader.get();
  }

  @Override
  public void put(Object key, Object value) {
    calls.add("put");
    if (failWrites) {
      throw FAILURE;
    }
  }

  @Override
  public ValueWrapper putIfAbsent(Object key, Object value) {
    calls.add("putIfAbsent");
    return () -> null;
  }

  @Override
  public void evict(Object key) {
    calls.add("evict");
    if (failWrites) {
      throw FAILURE;
    }
  }

  @Override
  public boolean evictIfPresent(Object key) {
    calls.add("evictIfPresent");
    return true;
  }

  @Override
  public void clear() {
    calls.add("clear");
    if (failWrites) {
      throw FAILURE;
    }
  }

  @Override
  public boolean invalidate() {
    calls.add("invalidate");
    return true;
  }

  /**
   * Returns the methods called, in order.
   *
   * @return the calls
   */
  public List<String> calls() {
    return List.copyOf(calls);
  }

  /**
   * Returns how many times a loader ran.
   *
   * @return the number of loads
   */
  public int loads() {
    return loads.get();
  }
}
