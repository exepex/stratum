package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import java.time.Clock;
import java.util.Arrays;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.cache.interceptor.SimpleCacheErrorHandler;

/**
 * Installs the cache layer on Spring's {@link CacheInterceptor}, which is fully configured with the
 * service's {@code CachingConfigurer} values before this bean is created. When the service set no
 * cache resolver, the hook sets the {@link StratumCacheResolver} and one wrapping {@link
 * StratumCacheErrorHandler}, so cached calls become fail-open, run their loader at most once, are
 * capped at {@code stratum.cache.max-caches} caches and skip Redis while it is unreachable. When
 * the service set a resolver — or only a cache manager, which Spring turns into one — the hook sets
 * nothing and the service's caching keeps Spring's behaviour. With no interceptor bean (AspectJ
 * mode) the layer stays off.
 */
@StratumInternal
public final class StratumCacheLayerHook {

  /**
   * Creates the hook and installs the layer, unless the service's own caching set-up says not to.
   *
   * @param cacheInterceptor Spring's interceptor, when proxy caching is on
   * @param cacheManager the service's cache manager
   * @param properties the module's settings
   * @param counters count the layer's failures and refused names, and measure its caches
   * @param beanFactory the service's beans, which name the cache manager for the metrics
   * @param redisRetryGate the gate in front of Redis caches, when the service has Spring Data Redis
   */
  public StratumCacheLayerHook(
      ObjectProvider<CacheInterceptor> cacheInterceptor,
      ObjectProvider<CacheManager> cacheManager,
      StratumCacheProperties properties,
      CacheCounters counters,
      ListableBeanFactory beanFactory,
      ObjectProvider<RedisRetryGate> redisRetryGate) {
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
    // service's own handler, a subclass of Spring's included, is wrapped and decides what reaches
    // the caller.
    var installed = interceptor.getErrorHandler();
    CacheErrorHandler serviceHandler =
        installed.getClass() == SimpleCacheErrorHandler.class ? null : installed;
    var handler = new StratumCacheErrorHandler(serviceHandler, throttle, counters);
    var gate = redisRetryGate.getIfAvailable();
    UnaryOperator<Cache> redisGate = gate == null ? UnaryOperator.identity() : gate::gated;
    interceptor.setCacheResolver(
        new StratumCacheResolver(
            manager,
            beanName(beanFactory, manager),
            properties.maxCaches(),
            throttle,
            counters,
            handler,
            redisGate));
    interceptor.setErrorHandler(handler);
  }

  /**
   * Returns the bean name of the cache manager the layer uses, once, at startup, or Spring Boot's
   * default name when this context does not hold it, such as a manager in a parent context.
   */
  private String beanName(ListableBeanFactory beanFactory, CacheManager manager) {
    return Arrays.stream(beanFactory.getBeanNamesForType(CacheManager.class))
        .filter(name -> manager.equals(beanFactory.getBean(name)))
        .findFirst()
        .orElse(CacheConstants.DEFAULT_CACHE_MANAGER);
  }
}
