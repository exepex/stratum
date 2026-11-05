package com.exepex.stratum.cache.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.cache.RedisCacheWriterCustomizer;
import com.exepex.stratum.cache.SizeLimitedRedisCacheWriter;
import com.exepex.stratum.cache.testsupport.CacheAssertions;
import com.exepex.stratum.cache.testsupport.ProductCatalog;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@DisplayName(
    "RedisCacheWriterAutoConfiguration makes Spring Boot's Redis caches clear with SCAN and limits"
        + " entry sizes")
class RedisCacheWriterAutoConfigurationTest {

  /** Spring Boot's Redis and cache set-up with Stratum's; nothing connects to Redis. */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  DataRedisAutoConfiguration.class,
                  CacheAutoConfiguration.class,
                  StratumCacheAutoConfiguration.class,
                  RedisCacheWriterAutoConfiguration.class));

  @Nested
  @DisplayName("with the module switched on or off")
  class ModuleSwitch {

    @Test
    @DisplayName("with the module on, Spring Boot's Redis cache manager gets the customizer")
    void createsCustomizer() {
      contextRunner.run(
          context -> {
            assertThat(context)
                .getBean(CacheConstants.REDIS_CACHE_WRITER)
                .isInstanceOf(RedisCacheWriterCustomizer.class);
            assertThat(context).getBean(CacheManager.class).isInstanceOf(RedisCacheManager.class);
          });
    }

    @Test
    @DisplayName(
        "with statistics on and the service's own Redis cache configuration, caches still get the"
            + " size limits")
    void limitsSizesWithServiceConfiguration() {
      contextRunner
          .withPropertyValues("spring.cache.redis.enable-statistics=true")
          .withBean(RedisCacheConfiguration.class, RedisCacheConfiguration::defaultCacheConfig)
          .run(
              context ->
                  assertThat(
                          context
                              .getBean(CacheManager.class)
                              .getCache(ProductCatalog.PRODUCTS)
                              .getNativeCache())
                      .isInstanceOf(SizeLimitedRedisCacheWriter.class));
    }

    @Test
    @DisplayName("with the module off, nothing is created")
    void createsNothingWithModuleOff() {
      contextRunner
          .withPropertyValues(CacheConstants.ENABLED + "=false")
          .run(context -> assertThat(context).doesNotHaveBean(RedisCacheWriterCustomizer.class));
    }

    @Test
    @DisplayName(
        "without Spring Data Redis, the module starts and its whole Redis auto-configuration stays"
            + " out")
    void createsNothingWithoutRedis() {
      contextRunner
          .withClassLoader(new FilteredClassLoader(RedisConnectionFactory.class))
          .run(
              context -> {
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(RedisCacheWriterCustomizer.class);
                CacheAssertions.assertLeftOut(context, RedisCacheWriterAutoConfiguration.class);
              });
    }
  }

  @Nested
  @DisplayName("without a Redis connection")
  class WithoutConnection {

    @Test
    @DisplayName(
        "with Spring Data Redis present but no Redis connection bean, the module starts and adds no"
            + " customizer")
    void createsNothingWithoutConnection() {
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(RedisCacheWriterAutoConfiguration.class))
          .run(
              context ->
                  assertThat(context)
                      .hasNotFailed()
                      .doesNotHaveBean(RedisCacheWriterCustomizer.class));
    }
  }

  @Nested
  @DisplayName("when the service or its framework decides otherwise")
  class ServiceChoices {

    @Test
    @DisplayName("the service's own customizer runs after Stratum's, so its writer wins")
    void runsServiceCustomizerLast() {
      var serviceWriter = mock(RedisCacheWriter.class);
      contextRunner
          .withBean(
              RedisCacheManagerBuilderCustomizer.class,
              () -> builder -> builder.cacheWriter(serviceWriter))
          .run(
              context ->
                  context.getBean(CacheManager.class).getCache(ProductCatalog.PRODUCTS).clear());

      verify(serviceWriter).clear(eq(ProductCatalog.PRODUCTS), any(byte[].class));
    }

    @Test
    @DisplayName("a service's bean of the same name replaces it")
    void backsOffForServiceBean() {
      RedisCacheManagerBuilderCustomizer serviceChoice = builder -> builder.enableStatistics();
      contextRunner
          .withBean(
              CacheConstants.REDIS_CACHE_WRITER,
              RedisCacheManagerBuilderCustomizer.class,
              () -> serviceChoice)
          .run(
              context ->
                  assertThat(context)
                      .doesNotHaveBean(RedisCacheWriterCustomizer.class)
                      .getBean(CacheConstants.REDIS_CACHE_WRITER)
                      .isSameAs(serviceChoice));
    }

    @Test
    @DisplayName("a company framework's auto-configuration with no order wins over Stratum's")
    void backsOffForCompanyBean() {
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  DataRedisAutoConfiguration.class,
                  RedisCacheWriterAutoConfiguration.class,
                  CompanyWriterAutoConfiguration.class))
          .run(
              context ->
                  assertThat(context)
                      .doesNotHaveBean(RedisCacheWriterCustomizer.class)
                      .getBean(CacheConstants.REDIS_CACHE_WRITER)
                      .isSameAs(CompanyWriterAutoConfiguration.WRITER_CHOICE));
    }

    @Test
    @DisplayName("the service's own Redis cache configuration keeps its time to live and prefix")
    void keepsServiceCacheConfiguration() {
      contextRunner
          .withPropertyValues(
              "spring.cache.redis.time-to-live=10m", "spring.cache.redis.key-prefix=orders:")
          .withBean(RedisCacheConfiguration.class, RedisCacheConfiguration::defaultCacheConfig)
          .run(
              context -> {
                var cache =
                    (RedisCache)
                        context.getBean(CacheManager.class).getCache(ProductCatalog.PRODUCTS);
                var configuration = cache.getCacheConfiguration();
                assertThat(configuration.getKeyPrefixFor(ProductCatalog.PRODUCTS))
                    .isEqualTo(ProductCatalog.PRODUCTS + "::");
                assertThat(configuration.getTtlFunction().getTimeToLive("sku-1", "price"))
                    .isEqualTo(Duration.ZERO);
              });
    }
  }

  /** A company framework's auto-configuration that declares no order. */
  @AutoConfiguration
  static class CompanyWriterAutoConfiguration {

    /** The company's writer choice, under the name of Stratum's customizer. */
    static final RedisCacheManagerBuilderCustomizer WRITER_CHOICE =
        builder -> builder.enableStatistics();

    /**
     * Returns the company's writer choice.
     *
     * @return the customizer
     */
    @Bean(CacheConstants.REDIS_CACHE_WRITER)
    RedisCacheManagerBuilderCustomizer companyWriterChoice() {
      return WRITER_CHOICE;
    }
  }
}
