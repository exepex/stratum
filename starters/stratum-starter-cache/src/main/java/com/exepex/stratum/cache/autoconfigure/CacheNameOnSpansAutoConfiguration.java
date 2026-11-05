package com.exepex.stratum.cache.autoconfigure;

import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.cache.CacheNameObservationFilter;
import com.exepex.stratum.cache.RedisCacheNames;
import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import io.lettuce.core.tracing.LettuceObservationContext;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Fallback;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Puts the cache name on the span of each Redis cache command, when the service observes Redis
 * through Lettuce, as Spring Boot does whenever it has an observation registry, unless {@code
 * stratum.cache.enabled=false}.
 */
@StratumInternal
@AutoConfiguration(
    after = DataRedisAutoConfiguration.class,
    afterName = CacheConstants.OBSERVATION_AUTO_CONFIGURATION)
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(CacheConstants.MODULE)
@ConditionalOnClass({
  RedisConnectionFactory.class,
  LettuceObservationContext.class,
  ObservationRegistry.class
})
@ConditionalOnBean({RedisConnectionFactory.class, ObservationRegistry.class})
public final class CacheNameOnSpansAutoConfiguration {

  /**
   * Creates the cache names spans can carry: {@code spring.cache.cache-names}, and each name
   * Stratum's cache layer admits. A service replaces it with a bean of the same name.
   *
   * @param environment the service's environment, from which the names and key prefix are resolved
   * @return the names
   */
  @Bean(CacheConstants.REDIS_CACHE_NAMES)
  @Fallback
  @ConditionalOnMissingBean(name = CacheConstants.REDIS_CACHE_NAMES)
  public RedisCacheNames stratumRedisCacheNames(Environment environment) {
    return RedisCacheNames.resolve(environment);
  }

  /**
   * Creates the filter that puts {@code cache.name} on Redis command spans; Spring Boot adds it to
   * the observation registry. A service replaces it with a bean of the same name.
   *
   * @param cacheNames the cache names spans can carry
   * @return the filter
   */
  @Bean(CacheConstants.CACHE_NAME_ON_SPANS)
  @Fallback
  @ConditionalOnMissingBean(name = CacheConstants.CACHE_NAME_ON_SPANS)
  public ObservationFilter stratumCacheNameOnSpans(RedisCacheNames cacheNames) {
    return new CacheNameObservationFilter(cacheNames);
  }
}
