package com.exepex.stratum.cache.testsupport;

import com.exepex.stratum.cache.CacheCounters;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters that record every failure and skip with their tags, so a test sees what the layer
 * counted without a metrics registry.
 */
public class RecordingCacheCounters implements CacheCounters {

  /** The failures counted, by {@code cache:operation}. */
  private final Map<String, AtomicLong> failures = new ConcurrentHashMap<>();

  /** The skips counted, by {@code cache:reason}. */
  private final Map<String, AtomicLong> skips = new ConcurrentHashMap<>();

  @Override
  public void failure(String cache, String operation) {
    failures.computeIfAbsent(cache + ":" + operation, key -> new AtomicLong()).incrementAndGet();
  }

  @Override
  public void skip(String cache, String reason) {
    skips.computeIfAbsent(cache + ":" + reason, key -> new AtomicLong()).incrementAndGet();
  }

  /**
   * Returns the failures counted for a cache and operation.
   *
   * @param cache the tag value counted
   * @param operation the operation
   * @return the count
   */
  public long failures(String cache, String operation) {
    var count = failures.get(cache + ":" + operation);
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
    var count = skips.get(cache + ":" + reason);
    return count == null ? 0 : count.get();
  }

  /**
   * Returns how many distinct failure series were counted.
   *
   * @return the number of series
   */
  public int failureSeries() {
    return failures.size();
  }

  /**
   * Returns how many distinct skip series were counted.
   *
   * @return the number of series
   */
  public int skipSeries() {
    return skips.size();
  }
}
