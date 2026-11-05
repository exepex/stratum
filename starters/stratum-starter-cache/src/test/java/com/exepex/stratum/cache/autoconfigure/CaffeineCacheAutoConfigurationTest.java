package com.exepex.stratum.cache.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.core.error.StratumException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.CaffeineSpec;
import com.github.benmanes.caffeine.cache.Policy;
import java.time.Duration;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.cache.autoconfigure.CacheManagerCustomizer;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;

@DisplayName("Spring Boot's Caffeine caches get Stratum's bound, expiry, statistics and null rule")
class CaffeineCacheAutoConfigurationTest {

  /** The cache the tests give their own settings. */
  private static final String REPORTS = "reports";

  /** A cache with no settings of its own. */
  private static final String PRICES = "prices";

  /** The bound the tests give {@value #REPORTS}. */
  private static final String REPORTS_MAX_ENTRIES =
      CacheConstants.CACHES + ".reports.max-entries=2";

  /** The time to live the tests give {@value #REPORTS}. */
  private static final String REPORTS_TIME_TO_LIVE =
      CacheConstants.CACHES + ".reports.time-to-live=30s";

  /** The bound of the service's own Caffeine builder. */
  private static final long SERVICE_MAX_ENTRIES = 5;

  /** A specification of the service's own, with a bound and no expiry. */
  private static final String SERVICE_SPEC =
      CacheConstants.CAFFEINE_SPEC + "=maximumSize=" + SERVICE_MAX_ENTRIES;

  /** The expiry the tests give {@value #REPORTS}. */
  private static final Duration REPORTS_OWN_TIME_TO_LIVE = Duration.ofSeconds(30);

  /** The most weight of a builder bounded by weight, not by entries. */
  private static final long MAX_WEIGHT = 100;

  /** Spring Boot's Caffeine cache set-up with Stratum's. */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withPropertyValues("spring.cache.type=caffeine")
          .withConfiguration(
              AutoConfigurations.of(
                  CacheAutoConfiguration.class,
                  StratumCacheAutoConfiguration.class,
                  CaffeineCacheAutoConfiguration.class));

  /** Returns the Caffeine cache behind a name. */
  private Cache<Object, Object> nativeCache(AssertableApplicationContext context, String name) {
    return ((CaffeineCache) context.getBean(CaffeineCacheManager.class).getCache(name))
        .getNativeCache();
  }

  /** Returns a cache's bound on its entries, empty when it has none. */
  private OptionalLong maximum(AssertableApplicationContext context, String name) {
    return nativeCache(context, name).policy().eviction().stream()
        .mapToLong(Policy.Eviction::getMaximum)
        .findFirst();
  }

  /** Returns how long a cache's entries live after each write, empty when they never expire. */
  private Duration expiry(AssertableApplicationContext context, String name) {
    return nativeCache(context, name).policy().expireAfterWrite().stream()
        .map(Policy.FixedExpiration::getExpiresAfter)
        .findFirst()
        .orElse(Duration.ZERO);
  }

  /** Returns whether Spring Boot's Caffeine cache manager stores null results. */
  private boolean storesNulls(AssertableApplicationContext context) {
    return context.getBean(CaffeineCacheManager.class).isAllowNullValues();
  }

  /** Checks that startup stopped with {@code SX-CACHE-1008} naming the cache and the setting. */
  private void assertUnusable(AssertableApplicationContext context, String setting) {
    assertThat(context).hasFailed();
    assertThat(context.getStartupFailure())
        .rootCause()
        .isInstanceOf(StratumException.class)
        .hasMessageStartingWith("SX-CACHE-1008")
        .hasMessageContaining("cache " + REPORTS)
        .hasMessageContaining(setting);
  }

  @Nested
  @DisplayName("by default")
  class Defaults {

    @Test
    @DisplayName("each cache holds at most 10,000 entries, which live 10 minutes")
    void boundsAndExpires() {
      contextRunner.run(context -> assertDefaultBuilder(context));
    }

    /** Checks that a cache has Stratum's bound and expiry. */
    private void assertDefaultBuilder(AssertableApplicationContext context) {
      assertThat(maximum(context, PRICES)).hasValue(CacheConstants.CAFFEINE_MAX_ENTRIES);
      assertThat(expiry(context, PRICES)).isEqualTo(CacheConstants.CAFFEINE_TIME_TO_LIVE);
    }

    @Test
    @DisplayName("hits and misses are recorded")
    void recordsStatistics() {
      contextRunner.run(context -> assertMissRecorded(context));
    }

    /** Reads a missing key once and checks the cache recorded the miss. */
    private void assertMissRecorded(AssertableApplicationContext context) {
      context.getBean(CaffeineCacheManager.class).getCache(PRICES).get("sku-1");
      assertThat(nativeCache(context, PRICES).stats().missCount()).isOne();
    }

    @Test
    @DisplayName("a null result is not stored, unless cache-null-values is true")
    void refusesNulls() {
      contextRunner.run(context -> assertThat(storesNulls(context)).isFalse());
      contextRunner
          .withPropertyValues(CacheConstants.CAFFEINE_CACHE_NULL_VALUES + "=true")
          .run(context -> assertThat(storesNulls(context)).isTrue());
    }
  }

  @Nested
  @DisplayName("the service's own Caffeine set-up wins")
  class ServiceWins {

    @Test
    @DisplayName("spring.cache.caffeine.spec replaces Stratum's builder; nulls stay refused")
    void specWins() {
      contextRunner.withPropertyValues(SERVICE_SPEC).run(context -> assertServiceBuilder(context));
    }

    @Test
    @DisplayName("a CaffeineSpec bean replaces Stratum's builder")
    void specBeanWins() {
      contextRunner
          .withBean(
              CaffeineSpec.class, () -> CaffeineSpec.parse("maximumSize=" + SERVICE_MAX_ENTRIES))
          .run(context -> assertServiceBuilder(context));
    }

    @Test
    @DisplayName("a Caffeine bean replaces Stratum's builder")
    void builderBeanWins() {
      contextRunner
          .withBean(Caffeine.class, () -> Caffeine.newBuilder().maximumSize(SERVICE_MAX_ENTRIES))
          .run(context -> assertServiceBean(context));
    }

    @Test
    @DisplayName("a Caffeine bean of a framework's auto-configuration with no order wins")
    void frameworkBuilderWins() {
      contextRunner
          .withConfiguration(AutoConfigurations.of(FrameworkCaffeineConfiguration.class))
          .run(context -> assertServiceBean(context));
    }

    /** Checks that Stratum's builder backed off and the caches use the service's. */
    private void assertServiceBean(AssertableApplicationContext context) {
      assertThat(context).doesNotHaveBean(CacheConstants.CAFFEINE);
      assertServiceBuilder(context);
    }

    /** Checks that the caches have the service's bound and no expiry, and still refuse nulls. */
    private void assertServiceBuilder(AssertableApplicationContext context) {
      assertThat(maximum(context, PRICES)).hasValue(SERVICE_MAX_ENTRIES);
      assertThat(expiry(context, PRICES)).isZero();
      assertThat(storesNulls(context)).isFalse();
    }

    @Test
    @DisplayName("a service customizer that stores nulls runs after Stratum's null rule")
    void serviceNullRuleWins() {
      CacheManagerCustomizer<CaffeineCacheManager> storeNulls =
          cacheManager -> cacheManager.setAllowNullValues(true);
      contextRunner
          .withBean(CacheManagerCustomizer.class, () -> storeNulls)
          .run(context -> assertThat(storesNulls(context)).isTrue());
    }
  }

  /** A company framework's auto-configuration that declares no order and its own builder. */
  @AutoConfiguration
  static class FrameworkCaffeineConfiguration {

    /**
     * Returns the company's builder.
     *
     * @return the builder
     */
    @Bean
    Caffeine<Object, Object> frameworkCaffeine() {
      return Caffeine.newBuilder().maximumSize(SERVICE_MAX_ENTRIES);
    }
  }

  @Nested
  @DisplayName("a single cache's own settings")
  class OwnSettings {

    @Test
    @DisplayName("max-entries bounds that cache only")
    void boundsOneCache() {
      contextRunner
          .withPropertyValues(REPORTS_MAX_ENTRIES)
          .run(context -> assertOnlyReportsBounded(context));
    }

    /** Checks that the reports cache has its own bound and another cache the default one. */
    private void assertOnlyReportsBounded(AssertableApplicationContext context) {
      assertThat(maximum(context, REPORTS)).hasValue(2);
      assertThat(maximum(context, PRICES)).hasValue(CacheConstants.CAFFEINE_MAX_ENTRIES);
    }

    @Test
    @DisplayName("time-to-live sets that cache's expiry only, and keeps the service's bound")
    void expiresOneCache() {
      contextRunner
          .withPropertyValues(REPORTS_TIME_TO_LIVE, SERVICE_SPEC + ",expireAfterWrite=1h")
          .run(context -> assertOnlyReportsExpiry(context));
    }

    /** Checks that the reports cache has its own expiry and keeps the service's bound. */
    private void assertOnlyReportsExpiry(AssertableApplicationContext context) {
      assertThat(expiry(context, REPORTS)).isEqualTo(REPORTS_OWN_TIME_TO_LIVE);
      assertThat(maximum(context, REPORTS)).hasValue(SERVICE_MAX_ENTRIES);
      assertThat(expiry(context, PRICES)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("max-entries stops startup with SX-CACHE-1008 when the builder has no maximumSize")
    void needsMaximumSize() {
      contextRunner
          .withPropertyValues(REPORTS_MAX_ENTRIES, CacheConstants.CAFFEINE_SPEC + "=recordStats")
          .run(context -> assertUnusable(context, CacheConstants.MAX_ENTRIES));
    }

    @Test
    @DisplayName("max-entries stops startup with SX-CACHE-1008 when the builder bounds weight")
    void refusesWeight() {
      contextRunner
          .withPropertyValues(REPORTS_MAX_ENTRIES)
          .withBean(
              Caffeine.class,
              () -> Caffeine.newBuilder().maximumWeight(MAX_WEIGHT).weigher((key, value) -> 1))
          .run(context -> assertUnusable(context, CacheConstants.MAX_ENTRIES));
    }

    @Test
    @DisplayName("time-to-live stops startup with SX-CACHE-1008 when the builder has no expiry")
    void needsExpiry() {
      contextRunner
          .withPropertyValues(REPORTS_TIME_TO_LIVE, SERVICE_SPEC)
          .run(context -> assertUnusable(context, CacheConstants.OWN_TIME_TO_LIVE));
    }

    @Test
    @DisplayName("a cache missing from spring.cache.cache-names stops startup with SX-CACHE-1008")
    void needsListedName() {
      contextRunner
          .withPropertyValues(REPORTS_MAX_ENTRIES, CacheConstants.CACHE_NAMES + "=" + PRICES)
          .run(context -> assertUnusable(context, "cache names"));
    }

    @Test
    @DisplayName("a service's bean of the same name replaces the customizer")
    void backsOffForServiceBean() {
      CacheManagerCustomizer<CaffeineCacheManager> serviceChoice =
          cacheManager -> cacheManager.setAllowNullValues(false);
      contextRunner
          .withPropertyValues(REPORTS_MAX_ENTRIES)
          .withBean(
              CacheConstants.CAFFEINE_CACHE_SETTINGS,
              CacheManagerCustomizer.class,
              () -> serviceChoice)
          .run(
              context ->
                  assertThat(maximum(context, REPORTS))
                      .hasValue(CacheConstants.CAFFEINE_MAX_ENTRIES));
    }
  }

  @Nested
  @DisplayName("stands back")
  class StandsBack {

    @Test
    @DisplayName("with the module off, Spring Boot's caches keep Caffeine's own behaviour")
    void moduleOff() {
      contextRunner
          .withPropertyValues(CacheConstants.ENABLED + "=false", REPORTS_MAX_ENTRIES)
          .run(context -> assertNoCaffeineBeans(context));
    }

    /** Checks that none of Stratum's Caffeine beans exists. */
    private void assertNoCaffeineBeans(AssertableApplicationContext context) {
      assertThat(context)
          .doesNotHaveBean(CacheConstants.CAFFEINE)
          .doesNotHaveBean(CacheConstants.CAFFEINE_NULL_VALUES)
          .doesNotHaveBean(CacheConstants.CAFFEINE_CACHE_SETTINGS);
    }

    @Test
    @DisplayName("without Caffeine on the class path, none of its beans exist")
    void withoutCaffeine() {
      contextRunner
          .withPropertyValues("spring.cache.type=simple")
          .withClassLoader(new FilteredClassLoader(Caffeine.class))
          .run(context -> assertNoCaffeineBeans(context));
    }
  }
}
