package com.exepex.stratum.cache.autoconfigure;

import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.cache.CacheCounters;
import com.exepex.stratum.cache.MicrometerCacheCounters;
import com.exepex.stratum.cache.StratumCacheLayerHook;
import com.exepex.stratum.cache.StratumCacheProperties;
import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.core.properties.StratumCustomizers;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.LazyInitializationExcludeFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.core.env.Environment;

/**
 * Installs the cache layer on Spring's cache interceptor: fail-open calls, a loader that runs at
 * most once, a cap on the number of caches and one warning per cache per interval, unless {@code
 * stratum.cache.enabled=false} or the service's own caching set-up wins. Without Micrometer, the
 * counters are no-ops and everything else works unchanged.
 */
@StratumInternal
@AutoConfiguration(after = CacheAutoConfiguration.class)
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(CacheConstants.MODULE)
public final class CacheLayerAutoConfiguration {

  /**
   * Stops startup with {@code SX-CORE-1004} when the service declares a cache customizer as a bean,
   * which would be silently ignored.
   *
   * @param beanFactory the service's beans
   */
  public CacheLayerAutoConfiguration(ListableBeanFactory beanFactory) {
    StratumCustomizers.rejectBeans(beanFactory);
  }

  /**
   * Creates the hook that installs the layer on Spring's cache interceptor. With no interceptor
   * bean (AspectJ mode) the hook does nothing and the layer is off.
   *
   * @param cacheInterceptor Spring's interceptor, when proxy caching is on
   * @param cacheManager the service's cache manager
   * @param environment the service's environment, from which the settings are resolved
   * @param counters count the layer's failures and refused names
   * @return the hook
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public StratumCacheLayerHook stratumCacheLayerHook(
      ObjectProvider<CacheInterceptor> cacheInterceptor,
      ObjectProvider<CacheManager> cacheManager,
      Environment environment,
      CacheCounters counters) {
    return new StratumCacheLayerHook(
        cacheInterceptor, cacheManager, StratumCacheProperties.resolve(environment), counters);
  }

  /**
   * Keeps the hook eager when the service sets {@code spring.main.lazy-initialization=true}, so the
   * layer is still installed before a cached call is made. Static because a {@code
   * BeanFactoryPostProcessor} reads the filter before ordinary beans exist, ST-ENG-47's exception,
   * as Spring Boot's own {@code scheduledBeanLazyInitializationExcludeFilter} is static. It backs
   * off by its name, not its type, because other filters, such as Spring Boot's, must not switch it
   * off.
   *
   * @return the filter
   */
  @Bean(CacheConstants.LAZY_INITIALIZATION_FILTER)
  @Fallback
  @ConditionalOnMissingBean(name = CacheConstants.LAZY_INITIALIZATION_FILTER)
  public static LazyInitializationExcludeFilter stratumCacheLayerLazyInitializationExcludeFilter() {
    return LazyInitializationExcludeFilter.forBeanTypes(StratumCacheLayerHook.class);
  }

  /**
   * Counts nothing when the service has no Micrometer, and yields to the Micrometer counters and to
   * a service's own.
   *
   * @return the no-op counters
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean(CacheCounters.class)
  public CacheCounters cacheCounters() {
    return CacheCounters.noop();
  }

  /**
   * Counts the layer's failures and refused names in Micrometer when it is on the class path. A
   * nested class, so Micrometer's types are only loaded then.
   */
  @StratumInternal
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule(CacheConstants.MODULE)
  @ConditionalOnClass(MeterRegistry.class)
  public static class MicrometerCacheCountersConfiguration {

    /**
     * Counts in the service's registry, when it has one.
     *
     * @param registry the service's metrics
     * @return the Micrometer counters
     */
    @Bean
    @Fallback
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean(CacheCounters.class)
    public MicrometerCacheCounters micrometerCacheCounters(MeterRegistry registry) {
      return new MicrometerCacheCounters(registry);
    }
  }
}
