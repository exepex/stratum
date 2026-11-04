package com.exepex.stratum.cache.testsupport;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;
import org.springframework.cache.support.NoOpCache;

/**
 * A cache whose reads, writes or the write after a load can be made to fail, as during a Redis
 * outage, and that records the calls it gets. A loader error is wrapped in a {@link
 * ValueRetrievalException}, as Spring's own caches wrap it.
 */
@RequiredArgsConstructor
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
  private final List<String> callsMade = new CopyOnWriteArrayList<>();

  /** How many times a loader ran. */
  private final AtomicInteger loadCount = new AtomicInteger();

  /**
   * Creates a healthy cache.
   *
   * @param name the cache's name
   */
  public StubCache(String name) {
    this(name, false, false);
  }

  @Override
  public String getName() {
    callsMade.add("getName");
    return name;
  }

  @Override
  public Object getNativeCache() {
    callsMade.add("getNativeCache");
    return this;
  }

  @Override
  public @Nullable ValueWrapper get(Object key) {
    callsMade.add("get");
    if (failReads) {
      throw FAILURE;
    }
    return null;
  }

  @Override
  public <T> @Nullable T get(Object key, Class<T> type) {
    callsMade.add("getTyped");
    if (failReads) {
      throw FAILURE;
    }
    return null;
  }

  @Override
  public <T> T get(Object key, Callable<T> valueLoader) {
    callsMade.add("getWithLoader");
    if (failReads) {
      throw FAILURE;
    }
    loadCount.incrementAndGet();
    // Spring's no-op cache runs the loader and wraps its error, as every Spring cache does.
    var value = new NoOpCache(name).get(key, valueLoader);
    if (failWrites) {
      throw FAILURE;
    }
    return value;
  }

  @Override
  public CompletableFuture<?> retrieve(Object key) {
    callsMade.add("retrieve");
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public <T> CompletableFuture<T> retrieve(Object key, Supplier<CompletableFuture<T>> valueLoader) {
    callsMade.add("retrieveWithLoader");
    return valueLoader.get();
  }

  @Override
  public void put(Object key, Object value) {
    callsMade.add("put");
    if (failWrites) {
      throw FAILURE;
    }
  }

  @Override
  public ValueWrapper putIfAbsent(Object key, Object value) {
    callsMade.add("putIfAbsent");
    return () -> null;
  }

  @Override
  public void evict(Object key) {
    callsMade.add("evict");
    if (failWrites) {
      throw FAILURE;
    }
  }

  @Override
  public boolean evictIfPresent(Object key) {
    callsMade.add("evictIfPresent");
    return true;
  }

  @Override
  public void clear() {
    callsMade.add("clear");
    if (failWrites) {
      throw FAILURE;
    }
  }

  @Override
  public boolean invalidate() {
    callsMade.add("invalidate");
    return true;
  }

  /**
   * Returns the methods called, in order.
   *
   * @return the calls
   */
  public List<String> calls() {
    return List.copyOf(callsMade);
  }

  /**
   * Returns how many times a loader ran.
   *
   * @return the number of loads
   */
  public int loads() {
    return loadCount.get();
  }
}
