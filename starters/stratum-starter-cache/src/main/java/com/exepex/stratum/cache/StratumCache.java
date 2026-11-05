package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.util.ReflectionUtils;

/**
 * A cache that forwards every call to the wrapped cache and adds the layer's run-once loader: when
 * {@link #get(Object, Callable)} fails after the loader ran — the write of the loaded value failed
 * — the failure is reported once, as a {@code put}, and the loader's result is still returned, so
 * the loader never runs twice for one call. Every other failure is rethrown for Spring to report to
 * the error handler, which decides whether the caller sees it. When the wrapped cache does not
 * store nulls, a null result is not stored and not reported, and a null {@code put} removes the old
 * entry, so the next call runs the method.
 */
@StratumInternal
@RequiredArgsConstructor
public class StratumCache implements Cache {

  /** The wrapped cache. */
  private final Cache delegate;

  /** Reports the failed write of a loaded value; the interceptor's handler, so once per error. */
  private final CacheErrorHandler errorHandler;

  /** Whether the wrapped cache refuses null values, as Redis does by Stratum's default. */
  private final boolean nullsRejected;

  @Override
  public String getName() {
    return delegate.getName();
  }

  @Override
  public Object getNativeCache() {
    return delegate.getNativeCache();
  }

  @Override
  public ValueWrapper get(Object key) {
    return delegate.get(key);
  }

  @Override
  public <T> T get(Object key, Class<T> type) {
    return delegate.get(key, type);
  }

  /**
   * Returns the cached value or loads it, running the loader at most once: when the call fails
   * after the loader ran, its recorded result is returned and the failed write is reported once. A
   * {@link ValueRetrievalException}, which carries the loader's own error, is always rethrown
   * unchanged, and so is a failure before the loader ran, such as a failed read. A null result that
   * the wrapped cache refuses to store is returned without a report.
   *
   * @param key the key
   * @param valueLoader loads the value on a miss
   * @param <T> the value's type
   * @return the value
   */
  @Override
  public <T> T get(Object key, Callable<T> valueLoader) {
    var loader = new RecordedLoader<>(valueLoader);
    try {
      return delegate.get(key, loader);
    } catch (ValueRetrievalException failure) {
      // Caffeine reports its refusal of the loaded null this way; any other is the loader's own
      // error, which Spring reports.
      if (refusedNull(loader)) {
        return null;
      }
      throw failure;
    } catch (RuntimeException failure) {
      if (!loader.hasRun()) {
        // A failure before the load — a failed read — is Spring's to report, so it is rethrown
        // unchanged, as CacheAspectSupport does for a loader's own error.
        ReflectionUtils.rethrowRuntimeException(failure);
      }
      if (refusedNull(loader)) {
        // The cache refused to store the null before writing anything: no failure to report.
        return null;
      }
      return afterFailedWrite(key, loader, failure);
    }
  }

  @Override
  public CompletableFuture<?> retrieve(Object key) {
    return delegate.retrieve(key);
  }

  @Override
  public <T> CompletableFuture<T> retrieve(Object key, Supplier<CompletableFuture<T>> valueLoader) {
    return delegate.retrieve(key, valueLoader);
  }

  /**
   * Stores the value, or, for a null the wrapped cache refuses, removes the old entry instead, so a
   * null result is never reported as a failure and the next call runs the method.
   *
   * @param key the key
   * @param value the value, or {@code null}
   */
  @Override
  public void put(Object key, @Nullable Object value) {
    if (nullsRejected && value == null) {
      delegate.evict(key);
      return;
    }
    delegate.put(key, value);
  }

  @Override
  public ValueWrapper putIfAbsent(Object key, Object value) {
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

  /** Returns whether the loader returned a null that the wrapped cache refuses to store. */
  private boolean refusedNull(RecordedLoader<?> loader) {
    return nullsRejected && loader.hasRun() && loader.value() == null;
  }

  /**
   * Reports the failed write of a loaded value once and returns the value. When the handler
   * rethrows, its error is wrapped so that Spring passes it on without a second report and the
   * caller receives it unchanged.
   */
  private <T> T afterFailedWrite(Object key, RecordedLoader<T> loader, RuntimeException failure) {
    try {
      errorHandler.handleCachePutError(failure, this, key, loader.value());
    } catch (RuntimeException handlerFailure) {
      throw new ValueRetrievalException(key, loader, handlerFailure);
    }
    return loader.value();
  }
}
