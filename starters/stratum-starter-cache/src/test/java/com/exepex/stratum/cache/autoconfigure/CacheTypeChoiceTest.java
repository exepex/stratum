package com.exepex.stratum.cache.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.cache.testsupport.ProductCatalog;
import com.github.benmanes.caffeine.cache.Policy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@DisplayName(
    "With no cache type set, Spring Boot picks bounded Caffeine caches, or Redis once the service"
        + " has it")
class CacheTypeChoiceTest {

  /**
   * Spring Boot's Redis and cache set-up with Stratum's, as a service gets it with no cache type.
   */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  DataRedisAutoConfiguration.class,
                  CacheAutoConfiguration.class,
                  StratumCacheAutoConfiguration.class,
                  CaffeineCacheAutoConfiguration.class));

  /** Returns the bound on the entries of the products cache. */
  private long productsMaximum(AssertableApplicationContext context) {
    var cache =
        (CaffeineCache) context.getBean(CacheManager.class).getCache(ProductCatalog.PRODUCTS);
    return cache
        .getNativeCache()
        .policy()
        .eviction()
        .map(Policy.Eviction::getMaximum)
        .orElseThrow();
  }

  @Test
  @DisplayName("without Spring Data Redis, the caches are Caffeine's, with 10,000 entries at most")
  void picksBoundedCaffeine() {
    contextRunner
        .withClassLoader(new FilteredClassLoader(RedisConnectionFactory.class))
        .run(context -> assertBoundedCaffeine(context));
  }

  /** Checks that the caches are Caffeine's, with Stratum's bound. */
  private void assertBoundedCaffeine(AssertableApplicationContext context) {
    assertThat(context).getBean(CacheManager.class).isInstanceOf(CaffeineCacheManager.class);
    assertThat(productsMaximum(context)).isEqualTo(CacheConstants.CAFFEINE_MAX_ENTRIES);
  }

  @Test
  @DisplayName("with Spring Data Redis and a Redis host, the caches are Redis's, not Caffeine's")
  void picksRedis() {
    contextRunner
        .withPropertyValues("spring.data.redis.host=cache.internal")
        .run(
            context ->
                assertThat(context)
                    .getBean(CacheManager.class)
                    .isInstanceOf(RedisCacheManager.class));
  }
}
