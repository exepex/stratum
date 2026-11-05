package com.exepex.stratum.cache.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.cache.CacheJsonValues;
import com.exepex.stratum.cache.JacksonRequiredCustomizer;
import com.exepex.stratum.cache.JsonValuesCustomizer;
import com.exepex.stratum.cache.TypedCacheValues;
import com.exepex.stratum.cache.testsupport.ProductCatalog;
import com.exepex.stratum.core.error.StratumException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.RedisSerializer;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("Redis cache values are stored as JSON where Stratum's cache layer reads them")
class JsonValuesAutoConfigurationTest {

  /** Spring Boot's Redis and cache set-up with Stratum's; nothing connects to Redis. */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  DataRedisAutoConfiguration.class,
                  CacheAutoConfiguration.class,
                  StratumCacheAutoConfiguration.class,
                  CacheLayerAutoConfiguration.class,
                  RedisCacheWriterAutoConfiguration.class));

  /** Returns the value reader of a Redis cache the context's cache manager makes. */
  private static Object valueReaderOf(AssertableApplicationContext context, String cacheName) {
    var cache = (RedisCache) context.getBean(CacheManager.class).getCache(cacheName);
    return cache.getCacheConfiguration().getValueSerializationPair().getReader();
  }

  @Nested
  @DisplayName("with Jackson present")
  class WithJackson {

    @Test
    @DisplayName(
        "every cache, those named in spring.cache.cache-names included, reads and writes JSON")
    void storesJson() {
      contextRunner
          .withPropertyValues("spring.cache.cache-names=" + ProductCatalog.PRODUCTS)
          .run(
              context -> {
                assertThat(context).hasSingleBean(TypedCacheValues.class);
                assertThat(context)
                    .getBean(CacheConstants.REDIS_JSON_VALUES)
                    .isInstanceOf(JsonValuesCustomizer.class);
                var json = context.getBean(CacheJsonValues.class);
                assertThat(valueReaderOf(context, ProductCatalog.PRODUCTS)).isSameAs(json);
                assertThat(valueReaderOf(context, "made-on-first-use")).isSameAs(json);
              });
    }

    @Test
    @DisplayName("null values stored by Spring Data still start with the JSON values in place")
    void keepsJsonWithNullValues() {
      contextRunner
          .withPropertyValues("spring.cache.redis.cache-null-values=true")
          .run(
              context ->
                  assertThat(valueReaderOf(context, ProductCatalog.PRODUCTS))
                      .isSameAs(context.getBean(CacheJsonValues.class)));
    }

    @Test
    @DisplayName("the service's own Redis cache configuration keeps its serializer")
    void serviceConfigurationWins() {
      var serviceValues = SerializationPair.fromSerializer(RedisSerializer.java());
      contextRunner
          .withBean(
              RedisCacheConfiguration.class,
              () -> RedisCacheConfiguration.defaultCacheConfig().serializeValuesWith(serviceValues))
          .run(
              context -> {
                assertThat(context).doesNotHaveBean(CacheJsonValues.class);
                assertThat(context).doesNotHaveBean(TypedCacheValues.class);
                assertThat(valueReaderOf(context, ProductCatalog.PRODUCTS))
                    .isSameAs(serviceValues.getReader());
              });
    }

    @Test
    @DisplayName(
        "a service's CachingConfigurer, which keeps Stratum's layer out, keeps Java values")
    void cachingConfigurerKeepsJavaValues() {
      contextRunner
          .withBean(CachingConfigurer.class, () -> new CachingConfigurer() {})
          .run(
              context -> {
                assertThat(context).doesNotHaveBean(CacheJsonValues.class);
                assertThat(valueReaderOf(context, ProductCatalog.PRODUCTS))
                    .isNotInstanceOf(CacheJsonValues.class);
              });
    }

    @Test
    @DisplayName("a customizer of the service's own runs after Stratum's and wins")
    void serviceCustomizerWins() {
      var serviceValues = SerializationPair.fromSerializer(RedisSerializer.java());
      contextRunner
          .withBean(
              RedisCacheManagerBuilderCustomizer.class,
              () ->
                  builder ->
                      builder.cacheDefaults(
                          builder.cacheDefaults().serializeValuesWith(serviceValues)))
          .run(
              context ->
                  assertThat(valueReaderOf(context, ProductCatalog.PRODUCTS))
                      .isSameAs(serviceValues.getReader()));
    }

    @Test
    @DisplayName("with the module off, no JSON bean is created")
    void moduleOff() {
      contextRunner
          .withPropertyValues(CacheConstants.ENABLED + "=false")
          .run(context -> assertThat(context).doesNotHaveBean(CacheJsonValues.class));
    }
  }

  @Nested
  @DisplayName("without Jackson")
  class WithoutJackson {

    /** The same set-up, with Jackson's mapper hidden from the class path. */
    private final ApplicationContextRunner noJackson =
        contextRunner.withClassLoader(new FilteredClassLoader(JsonMapper.class));

    @Test
    @DisplayName("startup stops with SX-CACHE-1007 when Spring Boot builds its Redis cache manager")
    void stopsWhenRedisCachesNeedIt() {
      noJackson.run(
          context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .rootCause()
                .isInstanceOf(StratumException.class)
                .hasMessageStartingWith("SX-CACHE-1007");
          });
    }

    @Test
    @DisplayName("a service whose caches are not in Redis starts")
    void startsWithOtherCacheType() {
      noJackson
          .withPropertyValues("spring.cache.type=simple")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(JacksonRequiredCustomizer.class);
              });
    }

    @Test
    @DisplayName("a service with its own cache manager starts")
    void startsWithServiceCacheManager() {
      noJackson
          .withBean(CacheManager.class, ConcurrentMapCacheManager::new)
          .run(context -> assertThat(context).hasNotFailed());
    }
  }
}
