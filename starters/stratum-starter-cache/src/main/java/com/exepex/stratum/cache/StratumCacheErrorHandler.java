package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.ErrorReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

/**
 * The cache layer's error handler: counts every failed cache operation, warns once per cache per
 * interval with {@code SX-CACHE-1002}, and then delegates to the service's own handler when it set
 * one, so the service's handler sees every error exactly once. With no service handler it swallows
 * the error, which is the fail-open: a failed read is a miss, a failed write is dropped, and the
 * real method runs. A cache key or value is never logged.
 */
@StratumInternal
@Slf4j
@RequiredArgsConstructor
public class StratumCacheErrorHandler implements CacheErrorHandler {

  /** The service's own handler, which decides what reaches the caller; {@code null} to swallow. */
  private final @Nullable CacheErrorHandler serviceHandler;

  /** Admits one warning per cache per interval and bounds the names counted on their own. */
  private final WarningThrottle throttle;

  /** Counts the failures. */
  private final CacheCounters counters;

  @Override
  public void handleCacheGetError(RuntimeException error, Cache cache, Object key) {
    report(CacheConstants.OPERATION_GET, cache);
    if (serviceHandler != null) {
      serviceHandler.handleCacheGetError(error, cache, key);
    }
  }

  @Override
  public void handleCachePutError(RuntimeException error, Cache cache, Object key, Object value) {
    report(CacheConstants.OPERATION_PUT, cache);
    if (serviceHandler != null) {
      serviceHandler.handleCachePutError(error, cache, key, value);
    }
  }

  @Override
  public void handleCacheEvictError(RuntimeException error, Cache cache, Object key) {
    report(CacheConstants.OPERATION_EVICT, cache);
    if (serviceHandler != null) {
      serviceHandler.handleCacheEvictError(error, cache, key);
    }
  }

  @Override
  public void handleCacheClearError(RuntimeException error, Cache cache) {
    report(CacheConstants.OPERATION_CLEAR, cache);
    if (serviceHandler != null) {
      serviceHandler.handleCacheClearError(error, cache);
    }
  }

  /**
   * Counts the failure under a bounded tag value and warns once per cache per interval, naming the
   * cache and the operation but never a key or value.
   */
  private void report(String operation, Cache cache) {
    var cacheName = cache.getName();
    counters.failure(throttle.tagValueFor(cacheName), operation);
    if (throttle.shouldWarn(cacheName) && log.isWarnEnabled()) {
      var report =
          ErrorReport.of(
              CacheErrorCode.CACHE_OPERATION_FAILED,
              "cache " + cacheName + ", operation " + operation);
      // The report already turns line breaks into spaces; SpotBugs only sees this replacement.
      log.warn(report.getCodeAndMessage().replaceAll("[\r\n]", " "));
    }
  }
}
