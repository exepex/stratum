package com.exepex.stratum.cache.testsupport;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

/**
 * A cache error handler that records every error and either swallows it or rethrows it, as a
 * service's own handler would.
 */
@RequiredArgsConstructor
public class RecordingCacheErrorHandler implements CacheErrorHandler {

  /** Whether every error is rethrown after it is recorded. */
  private final boolean rethrow;

  /**
   * The operations reported, in order, each with the cache's name, such as {@code get:products}.
   */
  private final List<String> reported = new CopyOnWriteArrayList<>();

  /** How often each operation was reported. */
  private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

  /** Creates a handler that swallows every error. */
  public RecordingCacheErrorHandler() {
    this(false);
  }

  @Override
  public void handleCacheGetError(RuntimeException error, Cache cache, Object key) {
    record("get", cache);
    if (rethrow) {
      throw error;
    }
  }

  @Override
  public void handleCachePutError(RuntimeException error, Cache cache, Object key, Object value) {
    record("put", cache);
    if (rethrow) {
      throw error;
    }
  }

  @Override
  public void handleCacheEvictError(RuntimeException error, Cache cache, Object key) {
    record("evict", cache);
    if (rethrow) {
      throw error;
    }
  }

  @Override
  public void handleCacheClearError(RuntimeException error, Cache cache) {
    record("clear", cache);
    if (rethrow) {
      throw error;
    }
  }

  /** Records one call. */
  private void record(String operation, Cache cache) {
    reported.add(operation + ":" + cache.getName());
    counts.computeIfAbsent(operation, key -> new AtomicInteger()).incrementAndGet();
  }

  /**
   * Returns the calls in order.
   *
   * @return each call, as {@code operation:cache}
   */
  public List<String> calls() {
    return List.copyOf(reported);
  }

  /**
   * Returns how often an operation was reported.
   *
   * @param operation the operation, such as {@code get}
   * @return the number of calls
   */
  public int times(String operation) {
    var count = counts.get(operation);
    return count == null ? 0 : count.get();
  }

  /**
   * Returns how many errors were reported in total.
   *
   * @return the number of calls
   */
  public int total() {
    return reported.size();
  }
}
