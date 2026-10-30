package com.exepex.stratum.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.CoreErrorCode;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.observability.CorrelationIdRecorder;
import com.exepex.stratum.observability.MetricTagGuard;
import com.exepex.stratum.observability.ObservabilityConstants;
import com.exepex.stratum.observability.ObservabilityPropertiesCustomizer;
import com.exepex.stratum.observability.TelemetryUrlFilter;
import com.exepex.stratum.observability.testsupport.ObservabilityTestData;
import com.exepex.stratum.observability.testsupport.RegionTagObservabilitySettings;
import com.exepex.stratum.test.StarterTestSupport;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.core.instrument.config.MeterFilterReply;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Hooks;

@DisplayName("ObservabilityAutoConfiguration creates the URL filter, tag guard and ID recorder")
class ObservabilityAutoConfigurationTest {

  private static final String MODULE_ON = ObservabilityConstants.ENABLED + "=true";

  private static final String REACTOR_CONTEXT_PROPAGATION = "spring.reactor.context-propagation";

  private static final String EXTRA_FORBIDDEN =
      ObservabilityConstants.PREFIX + ".tags.extra-forbidden";

  private static final String ALLOWED = ObservabilityConstants.PREFIX + ".tags.allowed";

  /** The built-in tag name the service keeps in its properties. */
  private static final String ORDER_TAG = "orderId";

  /** The tag name the service sets in its properties. */
  private static final String TENANT_TAG = "tenantId";

  /** The tag name the service sets in Java. */
  private static final String REGION_TAG = RegionTagObservabilitySettings.REGION_TAG;

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ValidationAutoConfiguration.class, ObservabilityAutoConfiguration.class));

  /** Returns what the context's guard answers for a metric with one tag. */
  private MeterFilterReply guardReplyForTag(AssertableApplicationContext context, String tagName) {
    return context
        .getBean(MetricTagGuard.class)
        .accept(ObservabilityTestData.counterTaggedWith(tagName));
  }

  @Nested
  @DisplayName("with the module switched on or off")
  class ModuleSwitch {

    @Test
    @DisplayName("with the module on, every bean is created")
    void createsBeans() {
      contextRunner
          .withPropertyValues(MODULE_ON)
          .run(
              context ->
                  assertThat(context)
                      .hasSingleBean(TelemetryUrlFilter.class)
                      .hasSingleBean(MetricTagGuard.class)
                      .hasSingleBean(CorrelationIdRecorder.class));
    }

    @Test
    @DisplayName("with the module unset or off, nothing is created")
    void moduleOffCreatesNothing() {
      contextRunner.run(context -> assertThat(context).doesNotHaveBean(MetricTagGuard.class));
      contextRunner
          .withPropertyValues(ObservabilityConstants.ENABLED + "=false")
          .run(
              context ->
                  assertThat(context)
                      .doesNotHaveBean(TelemetryUrlFilter.class)
                      .doesNotHaveBean(MetricTagGuard.class)
                      .doesNotHaveBean(CorrelationIdRecorder.class));
    }
  }

  @Nested
  @DisplayName("when the service or a company framework has its own beans")
  class BackingOff {

    @Test
    @DisplayName("the service's own beans replace Stratum's")
    void serviceBeansWin() {
      var serviceFilter = new TelemetryUrlFilter();
      var serviceGuard = new MetricTagGuard(List.of(), List.of());
      // The recorder is a span processor, which is closeable; the context closes it too.
      try (var serviceRecorder = new CorrelationIdRecorder()) {
        contextRunner
            .withPropertyValues(MODULE_ON)
            .withBean(TelemetryUrlFilter.class, () -> serviceFilter)
            .withBean(MetricTagGuard.class, () -> serviceGuard)
            .withBean(CorrelationIdRecorder.class, () -> serviceRecorder)
            .run(
                context -> {
                  assertThat(context.getBean(TelemetryUrlFilter.class)).isSameAs(serviceFilter);
                  assertThat(context.getBean(MetricTagGuard.class)).isSameAs(serviceGuard);
                  assertThat(context.getBean(CorrelationIdRecorder.class))
                      .isSameAs(serviceRecorder);
                });
      }
    }

    @Test
    @DisplayName("a company framework's auto-configuration with no order wins over Stratum's")
    void companyBeansWin() {
      new ApplicationContextRunner()
          .withPropertyValues(MODULE_ON)
          .withConfiguration(
              AutoConfigurations.of(
                  ValidationAutoConfiguration.class,
                  ObservabilityAutoConfiguration.class,
                  CompanyBeansAutoConfiguration.class))
          .run(
              context -> {
                assertThat(context.getBean(TelemetryUrlFilter.class))
                    .isSameAs(CompanyBeansAutoConfiguration.FILTER);
                assertThat(context.getBean(MetricTagGuard.class))
                    .isSameAs(CompanyBeansAutoConfiguration.GUARD);
                assertThat(context.getBean(CorrelationIdRecorder.class))
                    .isSameAs(CompanyBeansAutoConfiguration.RECORDER);
              });
    }
  }

  @Nested
  @DisplayName("when an optional library is missing")
  class OptionalLibraries {

    @Test
    @DisplayName(
        "without Spring's web classes, which make HTTP client spans, no URL filter is made")
    void withoutSpringWebCreatesNoFilter() {
      contextRunner
          .withPropertyValues(MODULE_ON)
          .withClassLoader(new FilteredClassLoader(UriComponentsBuilder.class))
          .run(context -> assertThat(context).doesNotHaveBean(TelemetryUrlFilter.class));
    }
  }

  @Nested
  @DisplayName("with tag settings from properties and Java")
  class Settings {

    @Test
    @DisplayName("the guard refuses and keeps the tag names set in properties")
    void guardUsesPropertyNames() {
      contextRunner
          .withPropertyValues(
              MODULE_ON, EXTRA_FORBIDDEN + "=" + TENANT_TAG, ALLOWED + "=" + ORDER_TAG)
          .run(
              context -> {
                assertThat(guardReplyForTag(context, TENANT_TAG)).isEqualTo(MeterFilterReply.DENY);
                assertThat(guardReplyForTag(context, ORDER_TAG))
                    .isEqualTo(MeterFilterReply.NEUTRAL);
              });
    }

    @Test
    @DisplayName("tag names set in a customizer listed in spring.factories win over properties")
    void javaNamesWin(@TempDir Path folder) throws IOException {
      var replies =
          StarterTestSupport.withCustomizers(
              folder,
              ObservabilityPropertiesCustomizer.class,
              List.of(RegionTagObservabilitySettings.class),
              this::regionAndTenantReplies);

      assertThat(replies).containsExactly(MeterFilterReply.DENY, MeterFilterReply.NEUTRAL);
    }

    @Test
    @DisplayName("a customizer declared as a bean stops startup with SX-CORE-1004")
    void customizerBeanStopsStartup() {
      contextRunner
          .withPropertyValues(MODULE_ON)
          .withBean(
              "regionTagCustomizer",
              RegionTagObservabilitySettings.class,
              RegionTagObservabilitySettings::new)
          .run(
              context ->
                  assertThat(context)
                      .getFailure()
                      .rootCause()
                      .isInstanceOf(StratumException.class)
                      .hasMessage(
                          "SX-CORE-1004: "
                              + CoreErrorCode.CUSTOMIZER_DECLARED_AS_BEAN.getDescription()
                              + ": customizer beans regionTagCustomizer"));
    }

    @Test
    @DisplayName("a blank tag name stops startup with SX-CORE-1003, naming the property")
    void blankTagNameStopsStartup() {
      contextRunner
          .withPropertyValues(MODULE_ON, EXTRA_FORBIDDEN + "[0]=")
          .run(
              context ->
                  assertThat(context)
                      .getFailure()
                      .rootCause()
                      .hasMessageStartingWith("SX-CORE-1003")
                      .hasMessageContaining(EXTRA_FORBIDDEN));
    }

    /**
     * Starts with {@code tenantId} refused in properties and returns what the guard answers for
     * {@code regionId} and then {@code tenantId}.
     */
    private List<MeterFilterReply> regionAndTenantReplies() {
      var replies = new ArrayList<MeterFilterReply>();
      contextRunner
          .withPropertyValues(MODULE_ON, EXTRA_FORBIDDEN + "=" + TENANT_TAG)
          .run(
              context ->
                  replies.addAll(
                      List.of(
                          guardReplyForTag(context, REGION_TAG),
                          guardReplyForTag(context, TENANT_TAG))));
      return replies;
    }
  }

  @Nested
  @DisplayName("when work moves to another thread")
  class ContextPropagation {

    @Test
    @DisplayName("with @EnableScheduling, @Async gets the pool Stratum adds, not Boot's scheduler")
    void asyncGetsPoolNotSchedulerWithScheduling() {
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  TaskExecutionAutoConfiguration.class,
                  TaskSchedulingAutoConfiguration.class,
                  ObservabilityAutoConfiguration.class))
          .withPropertyValues(MODULE_ON, "spring.task.execution.propagate-context=true")
          .withUserConfiguration(ServiceExecutorServiceBean.class, SchedulingEnabled.class)
          .run(
              context ->
                  assertThat(context.getBean(AsyncConfigurer.class).getAsyncExecutor())
                      .isSameAs(context.getBean("applicationTaskExecutor")));
    }

    @Test
    @DisplayName(
        "a service's ExecutorService bean is wrapped so its tasks keep the context, unless"
            + " stratum.context-propagation.executor-beans is false")
    void wrapsServiceExecutorServiceBeans() {
      contextRunner
          .withPropertyValues(MODULE_ON)
          .withUserConfiguration(ServiceExecutorServiceBean.class)
          .run(
              context ->
                  assertThat(context.getBean(ExecutorService.class))
                      .isInstanceOf(ContextExecutorService.class));
      contextRunner
          .withPropertyValues(MODULE_ON, "stratum.context-propagation.executor-beans=false")
          .withUserConfiguration(ServiceExecutorServiceBean.class)
          .run(
              context ->
                  assertThat(context.getBean(ExecutorService.class))
                      .isNotInstanceOf(ContextExecutorService.class));
    }

    @Test
    @DisplayName(
        "Reactor carries the context into every operator only when the setting it sees while beans"
            + " are created is auto, so a service's own limited wins")
    void reactorContextPropagationFollowsFinalSetting() {
      Hooks.disableAutomaticContextPropagation();
      contextRunner
          .withPropertyValues(MODULE_ON, REACTOR_CONTEXT_PROPAGATION + "=limited")
          .run(_ -> assertThat(Hooks.isAutomaticContextPropagationEnabled()).isFalse());
      contextRunner
          .withPropertyValues(MODULE_ON, REACTOR_CONTEXT_PROPAGATION + "=auto")
          .run(_ -> assertThat(Hooks.isAutomaticContextPropagationEnabled()).isTrue());
      Hooks.disableAutomaticContextPropagation();
    }
  }

  /** A company framework's auto-configuration that declares no order. */
  @AutoConfiguration
  static class CompanyBeansAutoConfiguration {

    /** The company's URL filter. */
    static final TelemetryUrlFilter FILTER = new TelemetryUrlFilter();

    /** The company's tag guard. */
    static final MetricTagGuard GUARD = new MetricTagGuard(List.of(), List.of());

    /** The company's correlation ID recorder. */
    static final CorrelationIdRecorder RECORDER = new CorrelationIdRecorder();

    /**
     * Returns the company's URL filter.
     *
     * @return the filter
     */
    @Bean
    TelemetryUrlFilter companyTelemetryUrlFilter() {
      return FILTER;
    }

    /**
     * Returns the company's tag guard.
     *
     * @return the guard
     */
    @Bean
    MetricTagGuard companyMetricTagGuard() {
      return GUARD;
    }

    /**
     * Returns the company's correlation ID recorder.
     *
     * @return the recorder
     */
    @Bean
    CorrelationIdRecorder companyCorrelationIdRecorder() {
      return RECORDER;
    }
  }

  /** A pool the service declares as an {@code ExecutorService} bean. */
  @Configuration(proxyBeanMethods = false)
  static class ServiceExecutorServiceBean {

    /**
     * Returns the service's pool.
     *
     * @return the pool
     */
    @Bean
    ExecutorService invoiceRenderingPool() {
      return Executors.newSingleThreadExecutor();
    }
  }

  /** Turns on {@code @Scheduled} jobs, which brings Spring Boot's scheduler. */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  static class SchedulingEnabled {}
}
