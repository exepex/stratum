package com.exepex.stratum.cache.autoconfigure;

import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.cache.ScanOnClearCustomizer;
import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Fallback;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Makes Spring Boot's Redis caches clear with SCAN when the service has Spring Data Redis, unless
 * {@code stratum.cache.enabled=false}.
 */
@StratumInternal
@AutoConfiguration(after = DataRedisAutoConfiguration.class)
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(CacheConstants.MODULE)
@ConditionalOnClass(RedisConnectionFactory.class)
public final class RedisCacheWriterAutoConfiguration {

  /**
   * Creates the customizer that clears Redis caches with SCAN. It runs before the service's own
   * customizers, so theirs win. A service replaces it with a bean of the same name; it backs off by
   * name, because a service may add its own customizers of the same type beside it.
   *
   * @param connectionFactory the connection to Redis
   * @return the customizer, which Spring Boot applies to its Redis cache manager
   */
  @Bean(CacheConstants.SCAN_ON_CLEAR)
  @Fallback
  @ConditionalOnMissingBean(name = CacheConstants.SCAN_ON_CLEAR)
  @ConditionalOnBean(RedisConnectionFactory.class)
  @Order(Ordered.HIGHEST_PRECEDENCE)
  public ScanOnClearCustomizer scanOnClear(RedisConnectionFactory connectionFactory) {
    return new ScanOnClearCustomizer(connectionFactory);
  }
}
