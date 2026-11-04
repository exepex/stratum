package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.ErrorReport;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.CacheOperationInvocationContext;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.cache.support.NoOpCache;

/**
 * Resolves the caches of a cached method: each admitted name is wrapped in one {@link
 * StratumCache}, so its calls are fail-open and its loader runs at most once. The first {@code
 * stratum.cache.max-caches} distinct names are admitted; a name beyond that is never resolved with
 * the cache manager, so no cache is created for it, and the method runs uncached through one shared
 * no-op cache. The refusal is counted per call as {@code stratum.cache.skips} under {@code
 * _over-limit} and warned once per interval with {@code SX-CACHE-1003}.
 */
@StratumInternal
@Slf4j
public class StratumCacheResolver implements CacheResolver {

  /** The service's cache manager, which creates an admitted cache on demand. */
  private final CacheManager cacheManager;

  /** The most admitted names, {@code stratum.cache.max-caches}. */
  private final int maxCaches;

  /** Admits one warning per interval on the shared {@code _over-limit} slot. */
  private final WarningThrottle throttle;

  /** Counts the refused names. */
  private final CacheCounters counters;

  /** Reports the failed write of a loaded value inside each wrapped cache. */
  private final StratumCacheErrorHandler errorHandler;

  /** The one cache every refused name shares: reads miss, writes do nothing, loaders run. */
  private final Cache overLimitCache = new NoOpCache(CacheConstants.OVER_LIMIT);

  /** The wrapped cache of each admitted name, whose key set is the admitted names. */
  private final Map<String, Cache> wrappedCaches = new ConcurrentHashMap<>();

  /**
   * Creates the resolver.
   *
   * @param cacheManager the service's cache manager
   * @param maxCaches the most admitted names
   * @param throttle admits one refusal warning per interval
   * @param counters counts the refused names
   * @param errorHandler the layer's handler, given to each wrapped cache
   */
  public StratumCacheResolver(
      CacheManager cacheManager,
      int maxCaches,
      WarningThrottle throttle,
      CacheCounters counters,
      StratumCacheErrorHandler errorHandler) {
    this.cacheManager = cacheManager;
    this.maxCaches = maxCaches;
    this.throttle = throttle;
    this.counters = counters;
    this.errorHandler = errorHandler;
  }

  /**
   * Returns the cache of each name the operation asks for. A refused name contributes the shared
   * no-op cache, so the collection is never empty and the operation still runs.
   *
   * @param context the operation
   * @return the caches, in the order of the operation's names
   */
  @Override
  public Collection<? extends Cache> resolveCaches(CacheOperationInvocationContext<?> context) {
    return context.getOperation().getCacheNames().stream().map(this::cacheFor).toList();
  }

  /** Returns the wrapped cache of an admitted name, or the shared no-op cache of a refused one. */
  private Cache cacheFor(String name) {
    var wrapped = wrappedCaches.get(name);
    if (wrapped != null) {
      return wrapped;
    }
    // Admission happens once per name, so one short lock bounds the map under any concurrency.
    synchronized (wrappedCaches) {
      var existing = wrappedCaches.get(name);
      if (existing != null) {
        return existing;
      }
      if (wrappedCaches.size() >= maxCaches) {
        return refused(name);
      }
      return wrappedCaches.computeIfAbsent(name, this::wrap);
    }
  }

  /** Wraps an admitted name's cache, which the cache manager creates on demand. */
  private Cache wrap(String name) {
    var cache = cacheManager.getCache(name);
    if (cache == null) {
      throw new IllegalStateException(
          "No cache named " + name + " could be resolved by " + cacheManager);
    }
    return new StratumCache(cache, errorHandler);
  }

  /** Counts a refused name, warns once per interval, and returns the shared no-op cache. */
  private Cache refused(String name) {
    counters.skip(CacheConstants.OVER_LIMIT, CacheConstants.REASON_CACHE_LIMIT);
    if (throttle.shouldWarn(CacheConstants.OVER_LIMIT) && log.isWarnEnabled()) {
      var report = ErrorReport.of(CacheErrorCode.CACHE_LIMIT_REACHED, "cache " + name);
      // The report already turns line breaks into spaces; SpotBugs only sees this replacement.
      log.warn(report.getCodeAndMessage().replaceAll("[\r\n]", " "));
    }
    return overLimitCache;
  }
}
