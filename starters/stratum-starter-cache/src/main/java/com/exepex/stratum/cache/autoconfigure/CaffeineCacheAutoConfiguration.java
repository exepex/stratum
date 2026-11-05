package com.exepex.stratum.cache.autoconfigure;

import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.cache.CaffeineCacheSettingsCustomizer;
import com.exepex.stratum.cache.StratumCacheProperties;
import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.cache.autoconfigure.CacheManagerCustomizer;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Fallback;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

/**
 * Gives Spring Boot's Caffeine caches Stratum's defaults when the service has Caffeine, unless
 * {@code stratum.cache.enabled=false}: each cache holds at most 10,000 entries, each entry lives 10
 * minutes, hits and misses are recorded for the cache metrics, a null result is not stored, and
 * single caches get their own bound and time to live. Spring Boot uses them only when it picks
 * Caffeine, as it does without Redis.
 */
@StratumInternal
@AutoConfiguration(after = CacheAutoConfiguration.class)
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(CacheConstants.MODULE)
@ConditionalOnClass({Caffeine.class, CaffeineCacheManager.class})
public final class CaffeineCacheAutoConfiguration {

  /**
   * Creates Stratum's Caffeine builder. Spring Boot uses it only when the service sets no {@code
   * spring.cache.caffeine.spec} and declares no {@code CaffeineSpec} or {@code Caffeine} bean, so
   * every one of those wins.
   *
   * @return the builder of every Caffeine cache
   */
  @Bean(CacheConstants.CAFFEINE)
  @Fallback
  @ConditionalOnMissingBean(Caffeine.class)
  public Caffeine<Object, Object> stratumCaffeine() {
    return Caffeine.newBuilder()
        .maximumSize(CacheConstants.CAFFEINE_MAX_ENTRIES)
        .expireAfterWrite(CacheConstants.CAFFEINE_TIME_TO_LIVE)
        .recordStats();
  }

  /**
   * Creates the customizer that sets {@code stratum.cache.caffeine.cache-null-values} on Spring
   * Boot's Caffeine cache manager. It runs before the service's own customizers, so theirs win; a
   * service replaces it with a bean of the same name.
   *
   * @param environment the service's environment, from which the settings are resolved
   * @return the customizer
   */
  @Bean(CacheConstants.CAFFEINE_NULL_VALUES)
  @Fallback
  @ConditionalOnMissingBean(name = CacheConstants.CAFFEINE_NULL_VALUES)
  @Order(Ordered.HIGHEST_PRECEDENCE)
  public CacheManagerCustomizer<CaffeineCacheManager> stratumCaffeineNullValues(
      Environment environment) {
    var cacheNullValues = StratumCacheProperties.resolve(environment).caffeine().cacheNullValues();
    return cacheManager -> cacheManager.setAllowNullValues(cacheNullValues);
  }

  /**
   * Creates the customizer that gives single Caffeine caches their own bound and time to live. It
   * runs after the service's own customizers, so it sets them on the caches of the final builder; a
   * service replaces it with a bean of the same name.
   *
   * @param environment the service's environment, from which the settings are resolved
   * @return the customizer
   */
  @Bean(CacheConstants.CAFFEINE_CACHE_SETTINGS)
  @Fallback
  @ConditionalOnMissingBean(name = CacheConstants.CAFFEINE_CACHE_SETTINGS)
  @Order(Ordered.LOWEST_PRECEDENCE)
  public CaffeineCacheSettingsCustomizer stratumCaffeineCacheSettings(Environment environment) {
    var properties = StratumCacheProperties.resolve(environment);
    return new CaffeineCacheSettingsCustomizer(properties.maxEntries(), properties.timesToLive());
  }
}
