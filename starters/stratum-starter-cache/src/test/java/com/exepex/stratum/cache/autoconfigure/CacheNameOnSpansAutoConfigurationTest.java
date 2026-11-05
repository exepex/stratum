package com.exepex.stratum.cache.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.cache.CacheNameObservationFilter;
import com.exepex.stratum.cache.RedisCacheNames;
import io.lettuce.core.tracing.LettuceObservationContext;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

@DisplayName(
    "The cache name on Redis spans is set up with Redis, Lettuce and an observation registry")
class CacheNameOnSpansAutoConfigurationTest {

  /**
   * Spring Boot's Redis set-up with Stratum's, without an observation registry; nothing connects.
   */
  private final ApplicationContextRunner withoutRegistry =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  DataRedisAutoConfiguration.class, CacheNameOnSpansAutoConfiguration.class));

  /** The same set-up with an observation registry, as Spring Boot makes with Micrometer. */
  private final ApplicationContextRunner contextRunner =
      withoutRegistry.withBean(ObservationRegistry.class, ObservationRegistry::create);

  /** Checks that neither the names nor the filter were made, and the context started. */
  private void assertNothingMade(AssertableApplicationContext context) {
    assertThat(context)
        .hasNotFailed()
        .doesNotHaveBean(RedisCacheNames.class)
        .doesNotHaveBean(CacheConstants.CACHE_NAME_ON_SPANS);
  }

  @Nested
  @DisplayName("with the module on")
  class ModuleOn {

    @Test
    @DisplayName("the names and the filter are made by default")
    void createsNamesAndFilter() {
      contextRunner.run(
          context ->
              assertThat(context)
                  .hasSingleBean(RedisCacheNames.class)
                  .getBean(CacheConstants.CACHE_NAME_ON_SPANS)
                  .isInstanceOf(CacheNameObservationFilter.class));
    }

    @Test
    @DisplayName("with the module off, nothing is made")
    void createsNothingWithModuleOff() {
      contextRunner
          .withPropertyValues(CacheConstants.ENABLED + "=false")
          .run(context -> assertNothingMade(context));
    }

    @Test
    @DisplayName("without an observation registry, nothing is made")
    void createsNothingWithoutRegistry() {
      withoutRegistry.run(context -> assertNothingMade(context));
    }
  }

  @Nested
  @DisplayName("when the service or its framework decides otherwise")
  class ServiceChoices {

    @Test
    @DisplayName(
        "cache names of any bean name and a filter named stratumCacheNameOnSpans replace Stratum's")
    void serviceBeansReplaceStratums() {
      var serviceNames = RedisCacheNames.none();
      ObservationFilter serviceFilter = context -> context;
      contextRunner
          .withBean("serviceCacheNames", RedisCacheNames.class, () -> serviceNames)
          .withBean(
              CacheConstants.CACHE_NAME_ON_SPANS, ObservationFilter.class, () -> serviceFilter)
          .run(context -> assertKept(context, serviceNames, serviceFilter));
    }

    @Test
    @DisplayName("a company framework's auto-configuration with no order wins over both beans")
    void companyBeansWin() {
      contextRunner
          .withConfiguration(AutoConfigurations.of(CompanySpanAutoConfiguration.class))
          .run(
              context ->
                  assertKept(
                      context,
                      CompanySpanAutoConfiguration.NAMES,
                      CompanySpanAutoConfiguration.FILTER));
    }

    /** Checks that the given names and filter took Stratum's places. */
    private void assertKept(
        AssertableApplicationContext context, RedisCacheNames names, ObservationFilter filter) {
      assertThat(context).hasSingleBean(RedisCacheNames.class);
      assertThat(context).getBean(RedisCacheNames.class).isSameAs(names);
      assertThat(context).getBean(CacheConstants.CACHE_NAME_ON_SPANS).isSameAs(filter);
    }
  }

  @Nested
  @DisplayName("with an optional library missing")
  class LibraryMissing {

    @Test
    @DisplayName(
        "without Lettuce's observation, as with Jedis, the module starts and makes nothing")
    void worksWithoutLettuce() {
      contextRunner
          .withClassLoader(new FilteredClassLoader(LettuceObservationContext.class))
          .run(context -> assertNothingMade(context));
    }

    @Test
    @DisplayName("without Micrometer's observation, the module starts and makes nothing")
    void worksWithoutMicrometer() {
      withoutRegistry
          .withClassLoader(new FilteredClassLoader(ObservationRegistry.class))
          .run(context -> assertNothingMade(context));
    }
  }

  /** A company framework's auto-configuration that declares no order. */
  @AutoConfiguration
  static class CompanySpanAutoConfiguration {

    /** The company's names, under a name of its own. */
    static final RedisCacheNames NAMES = mock(RedisCacheNames.class);

    /** The company's filter, under the name of Stratum's. */
    static final ObservationFilter FILTER = context -> context;

    /**
     * Returns the company's names.
     *
     * @return the names
     */
    @Bean
    RedisCacheNames companyNames() {
      return NAMES;
    }

    /**
     * Returns the company's filter.
     *
     * @return the filter
     */
    @Bean(CacheConstants.CACHE_NAME_ON_SPANS)
    ObservationFilter companyFilter() {
      return FILTER;
    }
  }
}
