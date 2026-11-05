package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;
import org.springframework.cache.support.NoOpCache;
import org.springframework.dao.DataAccessException;

/**
 * A Redis cache behind the {@link RedisRetryGate}: while the gate says to skip Redis, a read is a
 * miss, a loading read runs its loader, and a write, evict or clear is dropped, all without a Redis
 * call. Every Redis failure is passed to the gate and then rethrown, so the error handler still
 * counts and reports it.
 */
@StratumInternal
@RequiredArgsConstructor
public class RedisGatedCache implements Cache {

  /** The Redis cache. */
  private final Cache delegate;

  /** Decides whether Redis is called. */
  private final RedisRetryGate gate;

  /** Whether a timeout of this cache makes every Redis cache skip Redis. */
  private final boolean timeoutOpensGate;

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
    return gate.admits() ? tried(() -> delegate.get(key)) : null;
  }

  @Override
  public <T> @Nullable T get(Object key, @Nullable Class<T> type) {
    return gate.admits() ? tried(() -> delegate.get(key, type)) : null;
  }

  /**
   * Returns the cached value or loads it; while Redis is skipped, runs the loader without Redis.
   *
   * @param key the key
   * @param valueLoader loads the value on a miss
   * @param <T> the value's type
   * @return the value
   */
  @Override
  public <T> T get(Object key, Callable<T> valueLoader) {
    return gate.admits() ? tried(() -> delegate.get(key, valueLoader)) : load(key, valueLoader);
  }

  /**
   * Returns the cached value in the background; while Redis is skipped, a miss known at once. Like
   * a read, it may take a trial, which ends when Redis answers.
   *
   * @param key the key
   * @return the future value, or {@code null} for a miss
   */
  @Override
  public @Nullable CompletableFuture<?> retrieve(Object key) {
    return gate.admits() ? answered(() -> delegate.retrieve(key), () -> false) : null;
  }

  /**
   * Returns the cached value in the background or loads it. Once the loader starts, Redis has
   * answered with a miss, so the loader's own failure, such as a database timeout, never reaches
   * the gate.
   *
   * @param key the key
   * @param valueLoader loads the value on a miss
   * @param <T> the value's type
   * @return the future value
   */
  @Override
  public <T> CompletableFuture<T> retrieve(Object key, Supplier<CompletableFuture<T>> valueLoader) {
    if (!gate.admits()) {
      return valueLoader.get();
    }
    var missed = new AtomicBoolean();
    return answered(
        () -> delegate.retrieve(key, () -> loadedAfterMiss(missed, valueLoader)), missed::get);
  }

  /**
   * Writes the value. A write is sent in the background and never fails, so it cannot prove that
   * Redis is back: when it takes a trial, an immediate evict of its key proves it first.
   *
   * @param key the key
   * @param value the value
   */
  @Override
  public void put(Object key, @Nullable Object value) {
    written(() -> delegate.put(key, value), () -> evictThenPut(key, value));
  }

  @Override
  public @Nullable ValueWrapper putIfAbsent(Object key, @Nullable Object value) {
    return gate.admits() ? tried(() -> delegate.putIfAbsent(key, value)) : null;
  }

  @Override
  public void evict(Object key) {
    written(() -> delegate.evict(key), () -> delegate.evictIfPresent(key));
  }

  @Override
  public boolean evictIfPresent(Object key) {
    return gate.admits() && tried(() -> delegate.evictIfPresent(key));
  }

  /**
   * Clears the cache. Its SCAN carries no key of the cache, so it waits the global timeout, and its
   * timeout makes every Redis cache skip Redis, whatever this cache's own value.
   */
  @Override
  public void clear() {
    clearing().written(delegate::clear, delegate::invalidate);
  }

  /**
   * Clears the cache at once; its timeout makes every Redis cache skip Redis, as for {@link
   * #clear()}.
   *
   * @return whether the cache held entries
   */
  @Override
  public boolean invalidate() {
    return gate.admits() && clearing().tried(delegate::invalidate);
  }

  /**
   * Returns this cache as a clear sees it: one whose timeout makes every Redis cache skip Redis,
   * because a clear's SCAN waits the global timeout.
   */
  private RedisGatedCache clearing() {
    return timeoutOpensGate ? this : new RedisGatedCache(delegate, gate, true);
  }

  /**
   * Sends a write while Redis is in use. A trial sends its immediate form instead, because the
   * background form never fails; while Redis is skipped, the write is dropped.
   */
  private void written(Runnable inBackground, Runnable immediate) {
    if (gate.usesRedis()) {
      sentInBackground(inBackground);
    } else if (gate.admits()) {
      triedWithoutResult(immediate);
    }
  }

  /**
   * Sends a background write and tells the gate only of a failure: a sent write does not prove that
   * Redis answered, so it never ends a run of failures.
   */
  private void sentInBackground(Runnable redisCall) {
    try {
      redisCall.run();
    } catch (DataAccessException failure) {
      gate.failed(failure, timeoutOpensGate);
      throw failure;
    }
  }

  /** Calls Redis for a call without a result and tells the gate whether it was reached. */
  private void triedWithoutResult(Runnable redisCall) {
    tried(() -> ran(redisCall));
  }

  /** Runs a call without a result, so it can be passed where a result is expected. */
  private Boolean ran(Runnable redisCall) {
    redisCall.run();
    return Boolean.TRUE;
  }

  /** A trial write: the immediate evict proves Redis answers, then the write is sent. */
  private void evictThenPut(Object key, @Nullable Object value) {
    delegate.evictIfPresent(key);
    delegate.put(key, value);
  }

  /**
   * Calls Redis and tells the gate how it went. An error that is not Redis's own, such as a
   * loader's, releases a trial rather than ending it in success, so it never holds the trial.
   */
  private <T> T tried(Supplier<T> redisCall) {
    var settled = false;
    try {
      var result = redisCall.get();
      settled = true;
      gate.reached();
      return result;
    } catch (DataAccessException failure) {
      settled = true;
      gate.failed(failure, timeoutOpensGate);
      throw failure;
    } finally {
      if (!settled) {
        gate.released();
      }
    }
  }

  /**
   * Calls Redis in the background and tells the gate how it went once Redis answers. An error
   * thrown before the call is sent is told at once, as for {@link #tried}.
   */
  private <F extends @Nullable CompletableFuture<?>> F answered(
      Supplier<F> redisCall, BooleanSupplier toldAlready) {
    var settled = false;
    try {
      var future = redisCall.get();
      if (future != null) {
        settled = true;
        future.whenComplete((value, error) -> settle(error, toldAlready));
      }
      return future;
    } catch (DataAccessException failure) {
      settled = true;
      gate.failed(failure, timeoutOpensGate);
      throw failure;
    } finally {
      if (!settled) {
        gate.released();
      }
    }
  }

  /** Tells the gate that Redis answered with a miss, then starts the loader. */
  private <T> CompletableFuture<T> loadedAfterMiss(
      AtomicBoolean missed, Supplier<CompletableFuture<T>> valueLoader) {
    missed.set(true);
    gate.reached();
    return valueLoader.get();
  }

  /**
   * Tells the gate how a background call ended: answered, failed in Redis, or failed otherwise;
   * nothing when the gate was already told, once a loader started.
   */
  private void settle(@Nullable Throwable error, BooleanSupplier toldAlready) {
    if (toldAlready.getAsBoolean()) {
      return;
    }
    var cause = error instanceof CompletionException completion ? completion.getCause() : error;
    if (cause == null) {
      gate.reached();
    } else if (cause instanceof DataAccessException failure) {
      gate.failed(failure, timeoutOpensGate);
    } else {
      gate.released();
    }
  }

  /** Runs the loader without Redis, wrapping its error as a Redis cache would. */
  private <T> T load(Object key, Callable<T> valueLoader) {
    return new NoOpCache(getName()).get(key, valueLoader);
  }
}
