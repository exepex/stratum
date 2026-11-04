package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.cache.interceptor.SimpleCacheErrorHandler;

/**
 * Installs the cache layer on Spring's {@link CacheInterceptor}, which is fully configured with the
 * service's {@code CachingConfigurer} values before this bean is created. When the service set no
 * cache resolver, the hook sets the {@link StratumCacheResolver} and one wrapping {@link
 * StratumCacheErrorHandler}, so cached calls become fail-open, run their loader at most once and
 * are capped at {@code stratum.cache.max-caches} caches. When the service set a resolver — or only
 * a cache manager, which Spring turns into one — the hook sets nothing and the service's caching
 * keeps Spring's behaviour. With no interceptor bean (AspectJ mode) the layer stays off.
 */
@StratumInternal
public final class StratumCacheLayerHook {

  /**
   * Creates the hook and installs the layer, unless the service's own caching set-up says not to.
   *
   * @param cacheInterceptor Spring's interceptor, when proxy caching is on
   * @param cacheManager the service's cache manager
   * @param properties the module's settings
   * @param counters count the layer's failures and refused names
   */
  public StratumCacheLayerHook(
      ObjectProvider<CacheInterceptor> cacheInterceptor,
      ObjectProvider<CacheManager> cacheManager,
      StratumCacheProperties properties,
      CacheCounters counters) {
    var interceptor = cacheInterceptor.getIfAvailable();
    if (interceptor == null || interceptor.getCacheResolver() != null) {
      return;
    }
    var manager = cacheManager.getIfAvailable();
    if (manager == null) {
      return;
    }
    var throttle =
        new WarningThrottle(
            properties.warningInterval(), Clock.systemUTC(), properties.maxCaches());
    // Spring's default handler rethrows everything; swallowing instead is the fail-open. A
    // service's own handler is wrapped and decides what reaches the caller.
    var installed = interceptor.getErrorHandler();
    CacheErrorHandler serviceHandler =
        installed instanceof SimpleCacheErrorHandler ? null : installed;
    var handler = new StratumCacheErrorHandler(serviceHandler, throttle, counters);
    interceptor.setCacheResolver(
        new StratumCacheResolver(manager, properties.maxCaches(), throttle, counters, handler));
    interceptor.setErrorHandler(handler);
  }
}
