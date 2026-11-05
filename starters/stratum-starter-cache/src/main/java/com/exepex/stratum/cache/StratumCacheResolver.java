package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.ErrorReport;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.CacheOperationInvocationContext;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.cache.support.AbstractValueAdaptingCache;
import org.springframework.cache.support.NoOpCache;

/**
 * Resolves the caches of a cached method: each admitted name is wrapped in one {@link
 * StratumCache}, so its calls are fail-open, its loader runs at most once and a null result is not
 * stored where the cache refuses nulls, with a Redis cache behind the Redis retry gate; Spring
 * Boot's cache metrics measure it from its first use. A cache whose values are stored as JSON is
 * read as each calling method's return type. The first {@code stratum.cache.max-caches} distinct
 * names are admitted; a name beyond that is never resolved with the cache manager, so no cache is
 * created for it, and the method runs uncached through one shared no-op cache. The refusal is
 * counted per call as {@code stratum.cache.skips} under {@code _over-limit} and warned once per
 * interval with {@code SX-CACHE-1003}.
 */
@StratumInternal
@Slf4j
@RequiredArgsConstructor
public class StratumCacheResolver implements CacheResolver {

  /** The service's cache manager, which creates an admitted cache on demand. */
  private final CacheManager cacheManager;

  /** The cache manager's bean name, which Spring Boot's {@code cache.manager} tag comes from. */
  private final String cacheManagerName;

  /** The most admitted names, {@code stratum.cache.max-caches}. */
  private final int maxCaches;

  /** Admits one warning per interval on the shared {@code _over-limit} slot. */
  private final WarningThrottle throttle;

  /** Counts the refused names and has each admitted cache measured. */
  private final CacheCounters counters;

  /** Reports the failed write of a loaded value inside each wrapped cache. */
  private final StratumCacheErrorHandler errorHandler;

  /** Puts the Redis retry gate in front of a Redis cache; returns any other cache unchanged. */
  private final UnaryOperator<Cache> redisGate;

  /** Gives each call of a method its own value type, for caches that store values untyped. */
  private final CacheValueTyping typing;

  /** The one cache every refused name shares: reads miss, writes do nothing, loaders run. */
  private final Cache overLimitCache = new NoOpCache(CacheConstants.OVER_LIMIT);

  /** The wrapped cache of each admitted name, whose key set is the admitted names. */
  private final Map<String, Cache> wrappedCaches = new ConcurrentHashMap<>();

  /** The admitted names whose values are read as each calling method's type. */
  private final Set<String> typedNames = ConcurrentHashMap.newKeySet();

  /** Admits one name at a time, so the map never grows past the cap. */
  private final ReentrantLock admission = new ReentrantLock();

  /**
   * Returns the cache of each name the operation asks for, typed for the calling method where its
   * values are stored untyped. A refused name contributes the shared no-op cache, so the collection
   * is never empty and the operation still runs.
   *
   * @param context the operation
   * @return the caches, in the order of the operation's names
   */
  @Override
  public Collection<? extends Cache> resolveCaches(CacheOperationInvocationContext<?> context) {
    return context.getOperation().getCacheNames().stream()
        .map(name -> typedFor(name, context))
        .toList();
  }

  /** Returns a name's cache, as the calling method's typed view where its values need one. */
  private Cache typedFor(String name, CacheOperationInvocationContext<?> context) {
    var cache = cacheFor(name);
    return typedNames.contains(name) ? typing.typed(cache, context) : cache;
  }

  /** Returns the wrapped cache of an admitted name, or the shared no-op cache of a refused one. */
  private Cache cacheFor(String name) {
    var wrapped = wrappedCaches.get(name);
    if (wrapped != null) {
      return wrapped;
    }
    // Admission happens once per name, so one short lock bounds the map under any concurrency.
    admission.lock();
    try {
      var existing = wrappedCaches.get(name);
      if (existing != null) {
        return existing;
      }
      if (wrappedCaches.size() >= maxCaches) {
        return refused(name);
      }
      return wrappedCaches.computeIfAbsent(name, this::wrap);
    } finally {
      admission.unlock();
    }
  }

  /**
   * Wraps an admitted name's cache, which the cache manager creates on demand, and has it measured.
   * Both read the cache the manager made, so a decorator between it and Stratum's hides neither.
   */
  private Cache wrap(String name) {
    var cache = cacheManager.getCache(name);
    if (cache == null) {
      throw new IllegalStateException(
          "No cache named " + name + " could be resolved by " + cacheManager);
    }
    counters.bindCacheMetrics(cache, cacheManagerName);
    if (typing.appliesTo(cache)) {
      typedNames.add(name);
    }
    return new StratumCache(redisGate.apply(cache), errorHandler, rejectsNulls(cache));
  }

  /** Returns whether a cache refuses to store nulls, as Redis does with Stratum's default. */
  private boolean rejectsNulls(Cache cache) {
    return cache instanceof AbstractValueAdaptingCache adapting && !adapting.isAllowNullValues();
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
