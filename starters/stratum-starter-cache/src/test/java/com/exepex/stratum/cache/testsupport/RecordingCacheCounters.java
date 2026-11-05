package com.exepex.stratum.cache.testsupport;

import com.exepex.stratum.cache.CacheCounters;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.cache.Cache;

/**
 * Counters that record every failure and skip with their tags, and every cache given to be
 * measured, so a test sees what the layer counted without a metrics registry.
 */
public class RecordingCacheCounters implements CacheCounters {

  /** The failures counted, by {@code cache:operation}. */
  private final Map<String, AtomicLong> failuresByTags = new ConcurrentHashMap<>();

  /** The skips counted, by {@code cache:reason}. */
  private final Map<String, AtomicLong> skipsByTags = new ConcurrentHashMap<>();

  /** The caches given to be measured, in order. */
  private final List<Cache> measuredCaches = new CopyOnWriteArrayList<>();

  /** The cache manager name given with each measured cache, in order. */
  private final List<String> managerNamesMeasured = new CopyOnWriteArrayList<>();

  /** The Redis connect failures counted. */
  private final AtomicInteger redisConnectFailureCount = new AtomicInteger();

  @Override
  public void failure(String cache, String operation) {
    failuresByTags
        .computeIfAbsent(cache + ":" + operation, key -> new AtomicLong())
        .incrementAndGet();
  }

  @Override
  public void skip(String cache, String reason) {
    skipsByTags.computeIfAbsent(cache + ":" + reason, key -> new AtomicLong()).incrementAndGet();
  }

  @Override
  public void redisConnectFailure() {
    redisConnectFailureCount.incrementAndGet();
  }

  /**
   * Returns the Redis connect failures counted.
   *
   * @return the count
   */
  public int redisConnectFailures() {
    return redisConnectFailureCount.get();
  }

  @Override
  public void bindCacheMetrics(Cache cache, String cacheManagerName) {
    measuredCaches.add(cache);
    managerNamesMeasured.add(cacheManagerName);
  }

  /**
   * Returns the cache manager name given with each measured cache, in order.
   *
   * @return the names
   */
  public List<String> measuredManagerNames() {
    return List.copyOf(managerNamesMeasured);
  }

  /**
   * Returns the caches given to be measured, in order.
   *
   * @return the caches
   */
  public List<Cache> measured() {
    return List.copyOf(measuredCaches);
  }

  /**
   * Returns the failures counted for a cache and operation.
   *
   * @param cache the tag value counted
   * @param operation the operation
   * @return the count
   */
  public long failures(String cache, String operation) {
    var count = failuresByTags.get(cache + ":" + operation);
    return count == null ? 0 : count.get();
  }

  /**
   * Returns the skips counted for a cache and reason.
   *
   * @param cache the tag value counted
   * @param reason the reason
   * @return the count
   */
  public long skips(String cache, String reason) {
    var count = skipsByTags.get(cache + ":" + reason);
    return count == null ? 0 : count.get();
  }

  /**
   * Returns how many distinct failure series were counted.
   *
   * @return the number of series
   */
  public int failureSeries() {
    return failuresByTags.size();
  }

  /**
   * Returns how many distinct skip series were counted.
   *
   * @return the number of series
   */
  public int skipSeries() {
    return skipsByTags.size();
  }
}
