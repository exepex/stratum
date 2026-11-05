package com.exepex.stratum.cache.autoconfigure;

import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.cache.CacheCounters;
import com.exepex.stratum.cache.RedisCacheWriterCustomizer;
import com.exepex.stratum.cache.RedisRetryGate;
import com.exepex.stratum.cache.StratumCacheProperties;
import com.exepex.stratum.cache.WarningThrottle;
import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import io.lettuce.core.ClientOptions;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Sets up Redis caches when the service has Spring Data Redis, unless {@code
 * stratum.cache.enabled=false}: Spring Boot's Redis caches get Stratum's writer, which clears with
 * SCAN and bounds the size of each key and value; they skip Redis for a while after it could not be
 * reached; and, with Lettuce, their commands are refused at once while the connection is down.
 */
@StratumInternal
@AutoConfiguration(after = DataRedisAutoConfiguration.class)
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(CacheConstants.MODULE)
@ConditionalOnClass(RedisConnectionFactory.class)
public final class RedisCacheWriterAutoConfiguration {

  /**
   * Creates the customizer that sets Stratum's Redis cache writer. It runs before the service's own
   * customizers, so theirs win. A service replaces it with a bean of the same name; it backs off by
   * name, because a service may add its own customizers of the same type beside it.
   *
   * @param connectionFactory the connection to Redis
   * @param environment the service's environment, from which the size limits are resolved
   * @param counters count the hashed keys and the values not stored; no-ops when absent
   * @return the customizer, which Spring Boot applies to its Redis cache manager
   */
  @Bean(CacheConstants.REDIS_CACHE_WRITER)
  @Fallback
  @ConditionalOnMissingBean(name = CacheConstants.REDIS_CACHE_WRITER)
  @ConditionalOnBean(RedisConnectionFactory.class)
  @Order(Ordered.HIGHEST_PRECEDENCE)
  public RedisCacheWriterCustomizer stratumRedisCacheWriter(
      RedisConnectionFactory connectionFactory,
      Environment environment,
      ObjectProvider<CacheCounters> counters) {
    return new RedisCacheWriterCustomizer(
        connectionFactory,
        StratumCacheProperties.resolve(environment),
        counters.getIfAvailable(CacheCounters::noop));
  }

  /**
   * Creates the gate that makes Redis caches skip Redis for {@code
   * stratum.cache.redis-retry-interval} after it could not be reached or timed out.
   *
   * @param environment the service's environment, from which the settings are resolved
   * @param counters count the times Redis could not be reached; no-ops without Micrometer
   * @return the gate
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  @ConditionalOnBean(RedisConnectionFactory.class)
  public RedisRetryGate redisRetryGate(
      Environment environment, ObjectProvider<CacheCounters> counters) {
    var properties = StratumCacheProperties.resolve(environment);
    var clock = Clock.systemUTC();
    return new RedisRetryGate(
        properties.redisRetryInterval(),
        clock,
        new WarningThrottle(properties.warningInterval(), clock, 1),
        counters.getIfAvailable(CacheCounters::noop));
  }

  /**
   * Makes Lettuce refuse a command at once while its connection is down, instead of holding it
   * until the connection is back. A nested class, so Lettuce's types are only loaded when present.
   */
  @StratumInternal
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule(CacheConstants.MODULE)
  @ConditionalOnClass(ClientOptions.class)
  public static class LettuceRejectConfiguration {

    /**
     * Creates the customizer that refuses commands while disconnected. It runs before the service's
     * own customizers, so theirs win; a service replaces it with a bean of the same name.
     *
     * @return the customizer, which Spring Boot applies to Lettuce's client options
     */
    @Bean(CacheConstants.REJECT_WHILE_DISCONNECTED)
    @Fallback
    @ConditionalOnMissingBean(name = CacheConstants.REJECT_WHILE_DISCONNECTED)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public LettuceClientOptionsBuilderCustomizer stratumRejectWhileDisconnected() {
      return options ->
          options.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }
  }

  /**
   * Reports whether Redis caches use Redis, when Micrometer is on the class path. A nested class,
   * so Micrometer's types are only loaded then.
   */
  @StratumInternal
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule(CacheConstants.MODULE)
  @ConditionalOnClass(MeterBinder.class)
  public static class RedisAvailabilityMetricConfiguration {

    /**
     * Creates the gauge {@code stratum.cache.redis.available}: 1 while Redis caches use Redis, 0
     * from a failure until Redis is reached again.
     *
     * @param redisRetryGate the gate in front of Redis caches
     * @return the binder that registers the gauge
     */
    @Bean(CacheConstants.REDIS_AVAILABILITY_METRIC)
    @Fallback
    @ConditionalOnMissingBean(name = CacheConstants.REDIS_AVAILABILITY_METRIC)
    @ConditionalOnBean(RedisConnectionFactory.class)
    public MeterBinder stratumRedisAvailabilityMetric(RedisRetryGate redisRetryGate) {
      return registry ->
          Gauge.builder(
                  CacheConstants.METRIC_REDIS_AVAILABLE,
                  redisRetryGate,
                  gate -> gate.isAvailable() ? 1 : 0)
              .register(registry);
    }
  }
}
