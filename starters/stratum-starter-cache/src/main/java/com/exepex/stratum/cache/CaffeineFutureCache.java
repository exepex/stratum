package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;

/**
 * A Caffeine cache, built without Caffeine's async mode, that also serves cached methods returning
 * a {@code CompletableFuture}, a {@code Mono} or a {@code Flux}: a read is answered at once from
 * memory, and a value loaded on a miss is stored when the method's future completes, on the thread
 * that completes it. Concurrent misses of one key each run the method; blocking {@code sync=true}
 * methods still load once per key. Every other call is passed on unchanged.
 */
@StratumInternal
@RequiredArgsConstructor
public class CaffeineFutureCache implements Cache {

  /** The Caffeine cache. */
  private final Cache delegate;

  @Override
  public String getName() {
    return delegate.getName();
  }

  @Override
  public Object getNativeCache() {
    return delegate.getNativeCache();
  }

  @Override
  public @Nullable ValueWrapper get(Object key) {
    return delegate.get(key);
  }

  @Override
  public <T> @Nullable T get(Object key, @Nullable Class<T> type) {
    return delegate.get(key, type);
  }

  @Override
  public <T> @Nullable T get(Object key, Callable<T> valueLoader) {
    return delegate.get(key, valueLoader);
  }

  /**
   * Returns the cached entry as a completed future, or {@code null} on a miss.
   *
   * @param key the key
   * @return the entry, or {@code null}
   */
  @Override
  public @Nullable CompletableFuture<?> retrieve(Object key) {
    var cached = delegate.get(key);
    return cached == null ? null : CompletableFuture.completedFuture(cached);
  }

  /**
   * Returns the cached value, or loads it and stores it once the loader's future completes. A null
   * the cache refuses fails the store, which the cache layer turns into a null result.
   *
   * @param key the key
   * @param valueLoader loads the value on a miss
   * @param <T> the value's type
   * @return the future value
   */
  @Override
  public <T> CompletableFuture<T> retrieve(Object key, Supplier<CompletableFuture<T>> valueLoader) {
    var cached = delegate.get(key);
    if (cached != null) {
      return CompletableFuture.completedFuture((T) cached.get());
    }
    return valueLoader.get().thenApply(value -> stored(key, value));
  }

  @Override
  public void put(Object key, @Nullable Object value) {
    delegate.put(key, value);
  }

  @Override
  public @Nullable ValueWrapper putIfAbsent(Object key, @Nullable Object value) {
    return delegate.putIfAbsent(key, value);
  }

  @Override
  public void evict(Object key) {
    delegate.evict(key);
  }

  @Override
  public boolean evictIfPresent(Object key) {
    return delegate.evictIfPresent(key);
  }

  @Override
  public void clear() {
    delegate.clear();
  }

  @Override
  public boolean invalidate() {
    return delegate.invalidate();
  }

  /** Stores a loaded value and returns it. */
  private <T> T stored(Object key, T value) {
    delegate.put(key, value);
    return value;
  }
}
