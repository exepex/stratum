package com.exepex.stratum.cache.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.exepex.stratum.cache.CacheCommandTimeouts;
import com.exepex.stratum.cache.CacheCommandTimeoutsCheck;
import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.test.LogLineCapture;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.TimeoutOptions;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

@DisplayName("Single Redis caches get their own time to live and command timeout")
class PerCacheSettingsAutoConfigurationTest {

  /** The cache the tests give its own settings. */
  private static final String REPORTS = "reports";

  /** The time to live the tests give {@value #REPORTS}. */
  private static final Duration REPORTS_TIME_TO_LIVE = Duration.ofHours(1);

  /** The key of the time to live of {@value #REPORTS}. */
  private static final String REPORTS_TIME_TO_LIVE_KEY =
      CacheConstants.CACHES + ".reports.time-to-live=1h";

  /** The command timeout the tests give {@value #REPORTS}. */
  private static final Duration REPORTS_COMMAND_TIMEOUT = Duration.ofSeconds(3);

  /** The key of the command timeout of {@value #REPORTS}, above the 1s global one. */
  private static final String REPORTS_COMMAND_TIMEOUT_KEY =
      CacheConstants.CACHES + ".reports.command-timeout=3s";

  /** A command timeout a service sets in its own Lettuce customizer. */
  private static final Duration SERVICE_COMMAND_TIMEOUT = Duration.ofSeconds(5);

  /** Spring Boot's Redis and cache set-up with Stratum's; nothing connects to Redis. */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withPropertyValues(
              CacheConstants.REDIS_TIMEOUT + "=1s", CacheConstants.KEY_PREFIX + "=orders:")
          .withConfiguration(
              AutoConfigurations.of(
                  DataRedisAutoConfiguration.class,
                  CacheAutoConfiguration.class,
                  StratumCacheAutoConfiguration.class,
                  CacheLayerAutoConfiguration.class,
                  RedisCacheWriterAutoConfiguration.class));

  /** Returns the configuration Spring Data gave the cache {@value #REPORTS}. */
  private RedisCacheConfiguration reportsConfiguration(AssertableApplicationContext context) {
    var cache = (RedisCache) context.getBean(CacheManager.class).getCache(REPORTS);
    return cache.getCacheConfiguration();
  }

  /** Returns the source Lettuce asks for each command's timeout. */
  private TimeoutOptions.TimeoutSource timeoutSource(AssertableApplicationContext context) {
    return context
        .getBean(LettuceConnectionFactory.class)
        .getClientConfiguration()
        .getClientOptions()
        .orElseThrow()
        .getTimeoutOptions()
        .getSource();
  }

  /** Returns the command timeout Lettuce and Spring Data wait for. */
  private Duration sharedTimeout(AssertableApplicationContext context) {
    return context
        .getBean(LettuceConnectionFactory.class)
        .getClientConfiguration()
        .getCommandTimeout();
  }

  @Nested
  @DisplayName("time to live")
  class TimeToLive {

    @Test
    @DisplayName("a cache's own time to live is set, and its key prefix is kept")
    void setsOwnTimeToLive() {
      contextRunner
          .withPropertyValues(REPORTS_TIME_TO_LIVE_KEY)
          .run(context -> assertReportsCache(context, "orders:reports::"));
    }

    @Test
    @DisplayName(
        "a service customizer that changes the defaults still reaches the cache, which keeps its"
            + " own time to live")
    void appliesAfterServiceDefaults() {
      RedisCacheManagerBuilderCustomizer serviceDefaults =
          builder ->
              builder.cacheDefaults(
                  RedisCacheConfiguration.defaultCacheConfig().prefixCacheNameWith("billing:"));
      contextRunner
          .withPropertyValues(REPORTS_TIME_TO_LIVE_KEY)
          .withBean(RedisCacheManagerBuilderCustomizer.class, () -> serviceDefaults)
          .run(context -> assertReportsCache(context, "billing:reports::"));
    }

    /** Checks the reports cache keeps the key prefix and has its own time to live. */
    private void assertReportsCache(AssertableApplicationContext context, String keyPrefix) {
      var configuration = reportsConfiguration(context);
      assertThat(configuration.getKeyPrefixFor(REPORTS)).isEqualTo(keyPrefix);
      assertThat(configuration.getTtlFunction().getTimeToLive("sku-1", "report"))
          .isEqualTo(REPORTS_TIME_TO_LIVE);
    }

    @Test
    @DisplayName("a service's bean of the same name replaces it")
    void backsOffForServiceBean() {
      RedisCacheManagerBuilderCustomizer serviceChoice = builder -> builder.enableStatistics();
      contextRunner
          .withPropertyValues(REPORTS_TIME_TO_LIVE_KEY)
          .withBean(
              CacheConstants.CACHE_TIME_TO_LIVE,
              RedisCacheManagerBuilderCustomizer.class,
              () -> serviceChoice)
          .run(
              context ->
                  assertThat(context)
                      .getBean(CacheConstants.CACHE_TIME_TO_LIVE)
                      .isSameAs(serviceChoice));
    }
  }

  @Nested
  @DisplayName("command timeout")
  class CommandTimeout {

    @Test
    @DisplayName("Lettuce waits for the largest value and asks Stratum for each command's timeout")
    void raisesSharedTimeout() {
      contextRunner
          .withPropertyValues(REPORTS_COMMAND_TIMEOUT_KEY)
          .run(context -> assertRaisedTimeout(context));
    }

    /** Checks Lettuce waits for the reports cache's value and asks Stratum per command. */
    private void assertRaisedTimeout(AssertableApplicationContext context) {
      assertThat(sharedTimeout(context)).isEqualTo(REPORTS_COMMAND_TIMEOUT);
      assertThat(timeoutSource(context)).isInstanceOf(CacheCommandTimeouts.class);
    }

    @Test
    @DisplayName("a service's own command timeout runs after Stratum's, so it wins")
    void serviceCommandTimeoutWins() {
      LettuceClientConfigurationBuilderCustomizer serviceTimeout =
          builder -> builder.commandTimeout(SERVICE_COMMAND_TIMEOUT);
      contextRunner
          .withPropertyValues(REPORTS_COMMAND_TIMEOUT_KEY)
          .withBean(LettuceClientConfigurationBuilderCustomizer.class, () -> serviceTimeout)
          .run(context -> assertThat(sharedTimeout(context)).isEqualTo(SERVICE_COMMAND_TIMEOUT));
    }

    @Test
    @DisplayName("a service's own timeout options run after Stratum's, so they win")
    void serviceTimeoutOptionsWin() {
      var serviceOptions = TimeoutOptions.enabled(SERVICE_COMMAND_TIMEOUT);
      LettuceClientOptionsBuilderCustomizer serviceChoice =
          builder -> builder.timeoutOptions(serviceOptions);
      contextRunner
          .withPropertyValues(REPORTS_COMMAND_TIMEOUT_KEY)
          .withBean(LettuceClientOptionsBuilderCustomizer.class, () -> serviceChoice)
          .run(
              context ->
                  assertThat(
                          context
                              .getBean(LettuceConnectionFactory.class)
                              .getClientConfiguration()
                              .getClientOptions()
                              .orElseThrow()
                              .getTimeoutOptions())
                      .isSameAs(serviceOptions));
    }

    @Test
    @DisplayName("without a cache value, Spring Boot's timeout stays")
    void keepsBootTimeout() {
      contextRunner.run(
          context -> assertThat(sharedTimeout(context)).isEqualTo(Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName(
        "a value above 10 seconds with a 1 second global one stops startup with SX-CORE-1003,"
            + " naming the key but not the cache")
    void rejectsAboveCap() {
      contextRunner
          .withPropertyValues(CacheConstants.CACHES + ".reports.command-timeout=11s")
          .run(
              context ->
                  assertThat(context)
                      .getFailure()
                      .rootCause()
                      .hasMessageStartingWith("SX-CORE-1003")
                      .hasMessageContaining("stratum.cache.caches[*].command-timeout")
                      .hasMessageNotContaining("reports"));
    }
  }

  @Nested
  @DisplayName("without the Lettuce client")
  class WithoutLettuce {

    private final LogLineCapture warnings =
        new LogLineCapture(CacheCommandTimeoutsCheck.class.getName());

    /** Captures the warning. */
    @BeforeEach
    void startCapture() {
      warnings.start();
    }

    /** Stops capturing. */
    @AfterEach
    void stopCapture() {
      warnings.stop();
    }

    /** A context whose Redis client is not Lettuce, as with Jedis. */
    private ApplicationContextRunner otherClient() {
      return new ApplicationContextRunner()
          .withClassLoader(new FilteredClassLoader(ClientOptions.class))
          .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
          .withConfiguration(AutoConfigurations.of(RedisCacheWriterAutoConfiguration.class));
    }

    @Test
    @DisplayName("a cache's command timeout is warned as ignored, naming the cache")
    void warnsIgnoredTimeouts() {
      otherClient()
          .withPropertyValues(REPORTS_COMMAND_TIMEOUT_KEY)
          .run(context -> assertWarnedIgnored(context));
    }

    /**
     * Checks the context started without Stratum's timeouts and warned once, with the number of
     * caches but not their names, which may be secret.
     */
    private void assertWarnedIgnored(AssertableApplicationContext context) {
      assertThat(context).hasNotFailed().doesNotHaveBean(CacheCommandTimeouts.class);
      assertThat(warnings.onlyLine().getFormattedMessage())
          .contains(CacheConstants.CACHES + "[*].command-timeout")
          .contains("for 1 caches")
          .contains("Lettuce")
          .doesNotContain("reports");
    }

    @Test
    @DisplayName("a service's bean of the same name replaces the check, which then never warns")
    void backsOffForServiceBean() {
      SmartInitializingSingleton serviceCheck = () -> {};
      otherClient()
          .withPropertyValues(REPORTS_COMMAND_TIMEOUT_KEY)
          .withBean(
              CacheConstants.CACHE_COMMAND_TIMEOUTS_CHECK,
              SmartInitializingSingleton.class,
              () -> serviceCheck)
          .run(context -> assertServiceCheckWins(context, serviceCheck));
    }

    /** Checks the service's check replaced Stratum's, so nothing was warned. */
    private void assertServiceCheckWins(
        AssertableApplicationContext context, SmartInitializingSingleton serviceCheck) {
      assertThat(context)
          .getBean(CacheConstants.CACHE_COMMAND_TIMEOUTS_CHECK)
          .isSameAs(serviceCheck);
      assertThat(warnings.lines()).isEmpty();
    }

    @Test
    @DisplayName("with no cache command timeout, nothing is warned")
    void quietWithoutTimeouts() {
      otherClient().run(context -> assertThat(warnings.lines()).isEmpty());
    }

    @Test
    @DisplayName("with Lettuce, nothing is warned")
    void quietWithLettuce() {
      contextRunner
          .withPropertyValues(REPORTS_COMMAND_TIMEOUT_KEY)
          .run(context -> assertThat(warnings.lines()).isEmpty());
    }
  }
}
