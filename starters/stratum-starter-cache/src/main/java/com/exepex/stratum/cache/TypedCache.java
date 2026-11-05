package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;
import org.springframework.cache.support.NoOpCache;
import org.springframework.cache.support.SimpleValueWrapper;
import tools.jackson.databind.JavaType;

/**
 * One cached method's view of a Redis cache whose values are stored JSON: every read returns the
 * method's own type. A value that cannot be read is a miss: a plain read lets the method run and
 * its result replace the entry; a loading read removes the entry and loads once more, through the
 * same layer, so the loader still runs at most once. A value the method just loaded is returned as
 * the method's own object. Writes, evicts and clears are passed on unchanged.
 */
@StratumInternal
@RequiredArgsConstructor
public class TypedCache implements Cache {

  /** The layer's cache. */
  private final Cache delegate;

  /** The type the method's values are read as. */
  private final JavaType type;

  /** Reads stored values and reports those that cannot be read. */
  private final TypedCacheValues values;

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
    var cached = delegate.get(key);
    return cached == null ? null : typed(cached);
  }

  @Override
  public <T> @Nullable T get(Object key, @Nullable Class<T> requiredType) {
    var cached = get(key);
    return cached == null ? null : (T) cached.get();
  }

  /**
   * Returns the cached value as the method's type, or loads it. A stored null, which a cache that
   * keeps nulls returns, is a hit. An unreadable value is removed at once and the value is loaded
   * once more; if that read cannot be read either, the method runs without the cache.
   *
   * @param key the key
   * @param valueLoader loads the value on a miss
   * @param <T> the value's type
   * @return the value
   */
  @Override
  public <T> T get(Object key, Callable<T> valueLoader) {
    var loader = new RecordedLoader<>(valueLoader);
    var cached = delegate.get(key, loader);
    if (loader.hasRun()) {
      return loader.value();
    }
    if (cached == null) {
      // A stored null of a cache that keeps nulls: a hit.
      return cached;
    }
    var read = values.read(getName(), cached, type);
    if (read.isPresent()) {
      return (T) read.get();
    }
    // Not evict: Spring Data Redis may send that later, after the reload's read.
    delegate.evictIfPresent(key);
    var reloaded = delegate.get(key, loader);
    if (loader.hasRun()) {
      return loader.value();
    }
    if (reloaded == null) {
      return reloaded;
    }
    return (T) values.read(getName(), reloaded, type).orElseGet(() -> loadUncached(key, loader));
  }

  @Override
  public @Nullable CompletableFuture<?> retrieve(Object key) {
    var cached = delegate.retrieve(key);
    return cached == null ? null : cached.thenApply(this::typedRetrieved);
  }

  /**
   * Returns the cached value in the background as the method's type, or loads it. An unreadable
   * value is a miss: the loader runs and its result replaces the entry.
   *
   * @param key the key
   * @param valueLoader loads the value on a miss
   * @param <T> the value's type
   * @return the future value
   */
  @Override
  public <T> CompletableFuture<T> retrieve(Object key, Supplier<CompletableFuture<T>> valueLoader) {
    var loaded = new AtomicBoolean();
    // Spring's contract: this future is never null.
    var retrieved =
        Objects.requireNonNull(delegate.retrieve(key, () -> loadedOnMiss(loaded, valueLoader)));
    return retrieved.thenCompose(cached -> afterRetrieve(key, cached, loaded, valueLoader));
  }

  @Override
  public void put(Object key, @Nullable Object value) {
    delegate.put(key, value);
  }

  @Override
  public @Nullable ValueWrapper putIfAbsent(Object key, @Nullable Object value) {
    var existing = delegate.putIfAbsent(key, value);
    return existing == null ? null : typed(existing);
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

  /**
   * Returns the wrapped value as the method's type, or {@code null} when it cannot be read. A
   * wrapped null is a stored null, a hit, and is returned as it is.
   */
  private @Nullable ValueWrapper typed(ValueWrapper cached) {
    if (cached.get() == null) {
      return cached;
    }
    return values.read(getName(), cached.get(), type).map(SimpleValueWrapper::new).orElse(null);
  }

  /** Returns a value read in the background as the method's type; a wrapper stays a wrapper. */
  private @Nullable Object typedRetrieved(@Nullable Object cached) {
    return cached instanceof ValueWrapper wrapper
        ? typed(wrapper)
        : values.read(getName(), cached, type).orElse(null);
  }

  /** Marks that the cache missed and the loader runs, then starts it. */
  private <T> CompletableFuture<T> loadedOnMiss(
      AtomicBoolean loaded, Supplier<CompletableFuture<T>> valueLoader) {
    loaded.set(true);
    return valueLoader.get();
  }

  /**
   * Returns a loaded value or a stored null as it is, and a cached one as the method's type; an
   * unreadable one is loaded again and stored.
   */
  private <T> CompletableFuture<T> afterRetrieve(
      Object key, T cached, AtomicBoolean loaded, Supplier<CompletableFuture<T>> valueLoader) {
    if (loaded.get() || cached == null) {
      return CompletableFuture.completedFuture(cached);
    }
    return values
        .read(getName(), cached, type)
        .map(read -> CompletableFuture.completedFuture((T) read))
        .orElseGet(() -> valueLoader.get().thenApply(value -> stored(key, value)));
  }

  /**
   * Stores a value loaded in place of an unreadable one and returns it. A null is stored where the
   * cache keeps nulls; otherwise the cache removes the unreadable entry.
   */
  private <T> T stored(Object key, T value) {
    delegate.put(key, value);
    return value;
  }

  /** Runs the loader without the cache, wrapping its error as a cache would. */
  private <T> T loadUncached(Object key, RecordedLoader<T> loader) {
    return new NoOpCache(getName()).get(key, loader);
  }
}
