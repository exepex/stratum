package com.exepex.stratum.cache.autoconfigure;

import com.exepex.stratum.cache.CacheCommandTimeouts;
import com.exepex.stratum.cache.CacheCommandTimeoutsCheck;
import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.cache.CacheCounters;
import com.exepex.stratum.cache.CacheJsonValues;
import com.exepex.stratum.cache.CacheTimeToLiveCustomizer;
import com.exepex.stratum.cache.CacheTimeoutSettings;
import com.exepex.stratum.cache.JacksonRequiredCustomizer;
import com.exepex.stratum.cache.JsonValuesCustomizer;
import com.exepex.stratum.cache.RedisCacheWriterCustomizer;
import com.exepex.stratum.cache.RedisRetryGate;
import com.exepex.stratum.cache.StratumCacheProperties;
import com.exepex.stratum.cache.TypedCacheValues;
import com.exepex.stratum.cache.WarningThrottle;
import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import io.lettuce.core.ClientOptions;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Sets up Redis caches when the service has Spring Data Redis, unless {@code
 * stratum.cache.enabled=false}: Spring Boot's Redis caches get Stratum's writer, which clears with
 * SCAN and bounds the size of each key and value; they skip Redis for a while after it could not be
 * reached; single caches get their own time to live; with Lettuce, their commands are refused at
 * once while the connection is down and single caches get their own command timeout; and, where
 * Stratum's cache layer reads them, their values are stored as plain JSON.
 */
@StratumInternal
@AutoConfiguration(after = {DataRedisAutoConfiguration.class, StratumCacheAutoConfiguration.class})
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
   * stratum.cache.redis-retry-interval} after {@code stratum.cache.redis-failure-threshold} calls
   * in a row could not reach it or timed out.
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
        properties.redisFailureThreshold(),
        clock,
        new WarningThrottle(properties.warningInterval(), clock, 1),
        counters.getIfAvailable(CacheCounters::noop),
        CacheTimeoutSettings.resolve(environment).shortTimeoutCaches());
  }

  /**
   * Creates the customizer that gives single Redis caches their own time to live. It runs after the
   * service's own customizers, so it sets the time to live on each cache's final configuration; a
   * service replaces it with a bean of the same name.
   *
   * @param environment the service's environment, from which the settings are resolved
   * @return the customizer, which Spring Boot applies to its Redis cache manager
   */
  @Bean(CacheConstants.CACHE_TIME_TO_LIVE)
  @Fallback
  @ConditionalOnMissingBean(name = CacheConstants.CACHE_TIME_TO_LIVE)
  @ConditionalOnBean(RedisConnectionFactory.class)
  @Order(Ordered.LOWEST_PRECEDENCE)
  public CacheTimeToLiveCustomizer stratumCacheTimeToLive(Environment environment) {
    return new CacheTimeToLiveCustomizer(StratumCacheProperties.resolve(environment).timesToLive());
  }

  /**
   * Creates the check that warns, once at startup, when caches set their own command timeout but
   * the service's Redis client is not Lettuce, such as Jedis: then those timeouts are ignored. A
   * service replaces it with a bean of the same name.
   *
   * @param connectionFactory the connection to Redis
   * @param environment the service's environment, from which the settings are resolved
   * @return the check, which runs once every bean is created
   */
  @Bean(CacheConstants.CACHE_COMMAND_TIMEOUTS_CHECK)
  @Fallback
  @ConditionalOnMissingBean(name = CacheConstants.CACHE_COMMAND_TIMEOUTS_CHECK)
  @ConditionalOnBean(RedisConnectionFactory.class)
  public CacheCommandTimeoutsCheck stratumCacheCommandTimeoutsCheck(
      RedisConnectionFactory connectionFactory, Environment environment) {
    return new CacheCommandTimeoutsCheck(
        connectionFactory, StratumCacheProperties.resolve(environment).commandTimeouts().keySet());
  }

  /**
   * Makes Lettuce refuse a command at once while its connection is down, instead of holding it
   * until the connection is back, and gives single caches their own command timeout. A nested
   * class, so Lettuce's types are only loaded when present.
   */
  @StratumInternal
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule(CacheConstants.MODULE)
  @ConditionalOnClass(ClientOptions.class)
  public static class LettuceConfiguration {

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

    /**
     * Creates the timeouts of single Redis caches: Lettuce's connection timeout is raised to the
     * largest value, and each command gets its cache's own value or {@code
     * spring.data.redis.timeout}. Both hooks run before the service's own customizers, so theirs
     * win; a service replaces it with a bean of the same name.
     *
     * @param environment the service's environment, from which the settings are resolved
     * @return the timeouts, which Spring Boot applies to Lettuce's configuration and options
     * @throws com.exepex.stratum.core.error.StratumException {@code SX-CORE-1003} if a cache's
     *     value is above the larger of 10s and {@code spring.data.redis.timeout}
     */
    @Bean(CacheConstants.CACHE_COMMAND_TIMEOUTS)
    @Fallback
    @ConditionalOnMissingBean(name = CacheConstants.CACHE_COMMAND_TIMEOUTS)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public CacheCommandTimeouts stratumCacheCommandTimeouts(Environment environment) {
      return new CacheCommandTimeouts(CacheTimeoutSettings.resolve(environment));
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
     * while they skip it, until Redis is reached again.
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

  /**
   * Stores Redis cache values as plain JSON and reads them as each cached method's type, when
   * Jackson is present and Stratum's cache layer reads the caches: Spring's interceptor is in use
   * and the service declares no {@code CachingConfigurer} and no Spring Data {@code
   * RedisCacheConfiguration}, which keeps its own serializer. A nested class, so Jackson's types
   * are only loaded when present.
   */
  @StratumInternal
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule(CacheConstants.MODULE)
  @ConditionalOnClass(name = CacheConstants.JSON_MAPPER)
  @ConditionalOnBean({RedisConnectionFactory.class, CacheInterceptor.class})
  @ConditionalOnMissingBean({CachingConfigurer.class, RedisCacheConfiguration.class})
  public static class JsonValuesConfiguration {

    /**
     * Creates the JSON values, allowing the service's own packages, those of its {@code
     * SpringBootApplication}, and {@code stratum.cache.serialization.allowed-packages}.
     *
     * @param environment the service's environment, from which the settings are resolved
     * @param beanFactory the service's beans, which hold its own packages
     * @return the JSON values
     */
    @Bean(CacheConstants.CACHE_JSON_VALUES)
    @Fallback
    @ConditionalOnMissingBean
    public CacheJsonValues stratumCacheJsonValues(
        Environment environment, BeanFactory beanFactory) {
      var ownPackages =
          AutoConfigurationPackages.has(beanFactory)
              ? AutoConfigurationPackages.get(beanFactory)
              : List.<String>of();
      var allowed = StratumCacheProperties.resolve(environment).serialization().allowedPackages();
      return new CacheJsonValues(Stream.concat(ownPackages.stream(), allowed.stream()).toList());
    }

    /**
     * Creates the customizer that sets the JSON values on Spring Boot's Redis caches. It runs
     * before the service's own customizers, so theirs win; a service replaces it with a bean of the
     * same name.
     *
     * @param json the JSON values
     * @return the customizer
     */
    @Bean(CacheConstants.REDIS_JSON_VALUES)
    @Fallback
    @ConditionalOnMissingBean(name = CacheConstants.REDIS_JSON_VALUES)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public JsonValuesCustomizer stratumRedisCacheJsonValues(CacheJsonValues json) {
      return new JsonValuesCustomizer(json);
    }

    /**
     * Creates the typing that reads each cached method's values as its return type.
     *
     * @param json the JSON values
     * @param environment the service's environment, from which the settings are resolved
     * @param counters count the refused types and unreadable values; no-ops without Micrometer
     * @return the typing
     */
    @Bean
    @Fallback
    @ConditionalOnMissingBean
    public TypedCacheValues typedCacheValues(
        CacheJsonValues json, Environment environment, ObjectProvider<CacheCounters> counters) {
      var properties = StratumCacheProperties.resolve(environment);
      var clock = Clock.systemUTC();
      return new TypedCacheValues(
          json,
          properties,
          new WarningThrottle(properties.warningInterval(), clock, properties.maxCaches()),
          new WarningThrottle(properties.warningInterval(), clock, properties.maxCaches()),
          counters.getIfAvailable(CacheCounters::noop));
    }
  }

  /**
   * Stops startup with {@code SX-CACHE-1007} when Spring Boot builds its Redis cache manager under
   * the same conditions, but Jackson is missing.
   */
  @StratumInternal
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule(CacheConstants.MODULE)
  @ConditionalOnMissingClass(CacheConstants.JSON_MAPPER)
  @ConditionalOnBean({RedisConnectionFactory.class, CacheInterceptor.class})
  @ConditionalOnMissingBean({CachingConfigurer.class, RedisCacheConfiguration.class})
  public static class JacksonRequiredConfiguration {

    /**
     * Creates the customizer that stops startup; Spring Boot calls it only when it builds its Redis
     * cache manager.
     *
     * @return the customizer
     */
    @Bean(CacheConstants.REDIS_JACKSON_REQUIRED)
    @Fallback
    @ConditionalOnMissingBean(name = CacheConstants.REDIS_JACKSON_REQUIRED)
    public JacksonRequiredCustomizer stratumRedisCacheJacksonRequired() {
      return new JacksonRequiredCustomizer();
    }
  }
}
