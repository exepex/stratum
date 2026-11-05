package com.exepex.stratum.cache.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.cache.RedisRetryGate;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.ClientOptions.DisconnectedBehavior;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

@DisplayName(
    "The Redis retry gate, Lettuce's refusal while disconnected and the availability gauge are set"
        + " up with Redis")
class RedisRetryGateAutoConfigurationTest {

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

  /** Returns how Lettuce treats commands while disconnected, as Spring Boot configured it. */
  private DisconnectedBehavior disconnectedBehavior(AssertableApplicationContext context) {
    return context
        .getBean(LettuceConnectionFactory.class)
        .getClientConfiguration()
        .getClientOptions()
        .orElseThrow()
        .getDisconnectedBehavior();
  }

  @Nested
  @DisplayName("with the module on")
  class ModuleOn {

    @Test
    @DisplayName("the gate is created and Lettuce refuses commands while disconnected")
    void createsGateAndRejects() {
      contextRunner.run(context -> assertGateRejects(context));
    }

    @Test
    @DisplayName("the gauge stratum.cache.redis.available reads 1 before any call")
    void gaugeReadsAvailable() {
      contextRunner.run(context -> assertGaugeReadsOne(context));
    }

    @Test
    @DisplayName("with the module off, Lettuce keeps its own behaviour and no gate is made")
    void createsNothingWithModuleOff() {
      contextRunner
          .withPropertyValues(CacheConstants.ENABLED + "=false")
          .run(context -> assertNoGateAndLettuceDefault(context));
    }

    /** Checks that the gate exists and Lettuce refuses commands while disconnected. */
    private void assertGateRejects(AssertableApplicationContext context) {
      assertThat(context).hasSingleBean(RedisRetryGate.class);
      assertThat(disconnectedBehavior(context)).isEqualTo(DisconnectedBehavior.REJECT_COMMANDS);
    }

    /** Binds the gauge to a fresh registry and checks that it reads 1. */
    private void assertGaugeReadsOne(AssertableApplicationContext context) {
      MeterRegistry registry = new SimpleMeterRegistry();
      context.getBean(CacheConstants.REDIS_AVAILABILITY_METRIC, MeterBinder.class).bindTo(registry);
      assertThat(registry.get(CacheConstants.METRIC_REDIS_AVAILABLE).gauge().value()).isOne();
    }

    /** Checks that no gate exists and Lettuce keeps its own disconnected behaviour. */
    private void assertNoGateAndLettuceDefault(AssertableApplicationContext context) {
      assertThat(context).doesNotHaveBean(RedisRetryGate.class);
      assertThat(disconnectedBehavior(context))
          .isEqualTo(ClientOptions.DEFAULT_DISCONNECTED_BEHAVIOR);
    }
  }

  @Nested
  @DisplayName("when the service or its framework decides otherwise")
  class ServiceChoices {

    @Test
    @DisplayName("the service's own Lettuce customizer runs after Stratum's, so it wins")
    void serviceCustomizerWins() {
      contextRunner
          .withBean(
              LettuceClientOptionsBuilderCustomizer.class,
              () -> options -> options.disconnectedBehavior(DisconnectedBehavior.ACCEPT_COMMANDS))
          .run(
              context ->
                  assertThat(disconnectedBehavior(context))
                      .isEqualTo(DisconnectedBehavior.ACCEPT_COMMANDS));
    }

    @Test
    @DisplayName("a bean named stratumRejectWhileDisconnected replaces Stratum's customizer")
    void namedBeanReplacesCustomizer() {
      LettuceClientOptionsBuilderCustomizer serviceChoice = options -> options.autoReconnect(true);
      contextRunner
          .withBean(
              CacheConstants.REJECT_WHILE_DISCONNECTED,
              LettuceClientOptionsBuilderCustomizer.class,
              () -> serviceChoice)
          .run(context -> assertServiceCustomizerKept(context, serviceChoice));
    }

    @Test
    @DisplayName("the service's own gate replaces Stratum's")
    void serviceGateWins() {
      var serviceGate = mock(RedisRetryGate.class);
      contextRunner
          .withBean(RedisRetryGate.class, () -> serviceGate)
          .run(context -> assertThat(context).getBean(RedisRetryGate.class).isSameAs(serviceGate));
    }

    @Test
    @DisplayName("a bean named stratumRedisAvailabilityMetric replaces Stratum's gauge")
    void namedBeanReplacesGauge() {
      MeterBinder serviceChoice = registry -> registry.counter("service.redis");
      contextRunner
          .withBean(
              CacheConstants.REDIS_AVAILABILITY_METRIC, MeterBinder.class, () -> serviceChoice)
          .run(
              context ->
                  assertThat(context)
                      .getBean(CacheConstants.REDIS_AVAILABILITY_METRIC)
                      .isSameAs(serviceChoice));
    }

    @Test
    @DisplayName("a company framework's auto-configuration with no order wins over all three beans")
    void companyBeansWin() {
      contextRunner
          .withConfiguration(AutoConfigurations.of(CompanyRedisAutoConfiguration.class))
          .run(context -> assertCompanyBeansKept(context));
    }

    /** Checks that the company's gate, Lettuce customizer and gauge took Stratum's places. */
    private void assertCompanyBeansKept(AssertableApplicationContext context) {
      assertThat(context)
          .getBean(RedisRetryGate.class)
          .isSameAs(CompanyRedisAutoConfiguration.GATE);
      assertThat(context)
          .getBean(CacheConstants.REJECT_WHILE_DISCONNECTED)
          .isSameAs(CompanyRedisAutoConfiguration.LETTUCE_CHOICE);
      assertThat(context)
          .getBean(CacheConstants.REDIS_AVAILABILITY_METRIC)
          .isSameAs(CompanyRedisAutoConfiguration.GAUGE);
    }

    /** Checks that the service's customizer took Stratum's name and Lettuce keeps its default. */
    private void assertServiceCustomizerKept(
        AssertableApplicationContext context, LettuceClientOptionsBuilderCustomizer serviceChoice) {
      assertThat(context).getBean(CacheConstants.REJECT_WHILE_DISCONNECTED).isSameAs(serviceChoice);
      assertThat(disconnectedBehavior(context))
          .isEqualTo(ClientOptions.DEFAULT_DISCONNECTED_BEHAVIOR);
    }
  }

  @Nested
  @DisplayName("with an optional library missing")
  class LibraryMissing {

    @Test
    @DisplayName("without Micrometer, the gate still works and no gauge is made")
    void worksWithoutMicrometer() {
      contextRunner
          .withClassLoader(new FilteredClassLoader(MeterBinder.class))
          .run(
              context ->
                  assertThat(context)
                      .hasNotFailed()
                      .hasSingleBean(RedisRetryGate.class)
                      .doesNotHaveBean(CacheConstants.REDIS_AVAILABILITY_METRIC));
    }

    @Test
    @DisplayName("without Lettuce, the module starts and adds no Lettuce customizer")
    void worksWithoutLettuce() {
      contextRunner
          .withClassLoader(new FilteredClassLoader(ClientOptions.class))
          .run(
              context ->
                  assertThat(context)
                      .hasNotFailed()
                      .doesNotHaveBean(CacheConstants.REJECT_WHILE_DISCONNECTED));
    }
  }

  /** A company framework's auto-configuration that declares no order. */
  @AutoConfiguration
  static class CompanyRedisAutoConfiguration {

    /** The company's gate. */
    static final RedisRetryGate GATE = mock(RedisRetryGate.class);

    /** The company's Lettuce choice, under the name of Stratum's customizer. */
    static final LettuceClientOptionsBuilderCustomizer LETTUCE_CHOICE =
        options -> options.autoReconnect(true);

    /** The company's gauge, under the name of Stratum's. */
    static final MeterBinder GAUGE = registry -> registry.counter("company.redis");

    /**
     * Returns the company's gate.
     *
     * @return the gate
     */
    @Bean
    RedisRetryGate companyGate() {
      return GATE;
    }

    /**
     * Returns the company's Lettuce choice.
     *
     * @return the customizer
     */
    @Bean(CacheConstants.REJECT_WHILE_DISCONNECTED)
    LettuceClientOptionsBuilderCustomizer companyLettuceChoice() {
      return LETTUCE_CHOICE;
    }

    /**
     * Returns the company's gauge.
     *
     * @return the binder
     */
    @Bean(CacheConstants.REDIS_AVAILABILITY_METRIC)
    MeterBinder companyGauge() {
      return GAUGE;
    }
  }
}
