package com.exepex.stratum.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.CoreErrorCode;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.core.properties.StratumPropertiesCustomizer;
import com.exepex.stratum.observability.CorrelationIdRecorder;
import com.exepex.stratum.observability.MetricTagGuard;
import com.exepex.stratum.observability.ObservabilityConstants;
import com.exepex.stratum.observability.ObservabilityProperties;
import com.exepex.stratum.observability.TelemetryUrlFilter;
import com.exepex.stratum.observability.testsupport.JavaConfigurationTestData;
import com.exepex.stratum.observability.testsupport.MetricTestData;
import com.exepex.stratum.observability.testsupport.RegionTagObservabilitySettings;
import io.micrometer.core.instrument.config.MeterFilterReply;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.util.UriComponentsBuilder;

@DisplayName("ObservabilityAutoConfiguration creates the URL filter, tag guard and ID recorder")
class ObservabilityAutoConfigurationTest {

  private static final String MODULE_ON = ObservabilityConstants.ENABLED + "=true";

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
    return context.getBean(MetricTagGuard.class).accept(MetricTestData.counterTaggedWith(tagName));
  }

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

  @Test
  @DisplayName("without Spring's web classes, which make HTTP client spans, no URL filter is made")
  void withoutSpringWebCreatesNoFilter() {
    contextRunner
        .withPropertyValues(MODULE_ON)
        .withClassLoader(new FilteredClassLoader(UriComponentsBuilder.class))
        .run(context -> assertThat(context).doesNotHaveBean(TelemetryUrlFilter.class));
  }

  @Test
  @DisplayName("the service's own beans replace Stratum's")
  void serviceBeansWin() {
    contextRunner
        .withPropertyValues(MODULE_ON)
        .withUserConfiguration(ServiceBeansConfiguration.class)
        .run(
            context -> {
              var serviceBeans = context.getBean(ServiceBeansConfiguration.class);
              assertThat(context.getBean(TelemetryUrlFilter.class)).isSameAs(serviceBeans.filter);
              assertThat(context.getBean(MetricTagGuard.class)).isSameAs(serviceBeans.guard);
              assertThat(context.getBean(CorrelationIdRecorder.class))
                  .isSameAs(serviceBeans.recorder);
            });
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

  @Test
  @DisplayName("the guard refuses and keeps the tag names set in properties")
  void guardUsesPropertyNames() {
    contextRunner
        .withPropertyValues(
            MODULE_ON, EXTRA_FORBIDDEN + "=" + TENANT_TAG, ALLOWED + "=" + ORDER_TAG)
        .run(
            context -> {
              assertThat(guardReplyForTag(context, TENANT_TAG)).isEqualTo(MeterFilterReply.DENY);
              assertThat(guardReplyForTag(context, ORDER_TAG)).isEqualTo(MeterFilterReply.NEUTRAL);
            });
  }

  @Test
  @DisplayName("tag names set in a customizer listed in spring.factories win over properties")
  void javaNamesWin(@TempDir Path folder) throws IOException {
    var replies =
        JavaConfigurationTestData.withCustomizers(
            folder, List.of(RegionTagObservabilitySettings.class), this::regionAndTenantReplies);

    assertThat(replies).containsExactly(MeterFilterReply.DENY, MeterFilterReply.NEUTRAL);
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

  @Test
  @DisplayName("a customizer declared as a bean stops startup with SX-CORE-1004")
  void customizerBeanStopsStartup() {
    contextRunner
        .withPropertyValues(MODULE_ON)
        .withUserConfiguration(CustomizerAsBeanConfiguration.class)
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(StratumException.class)
                    .hasMessage(
                        "SX-CORE-1004: "
                            + CoreErrorCode.CUSTOMIZER_DECLARED_AS_BEAN.getDescription()
                            + ": ObservabilityProperties customizer beans regionTagCustomizer"));
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

  /** A service's configuration with its own beans. */
  @Configuration(proxyBeanMethods = false)
  static class ServiceBeansConfiguration {

    /** The service's URL filter. */
    final TelemetryUrlFilter filter = new TelemetryUrlFilter();

    /** The service's tag guard. */
    final MetricTagGuard guard = new MetricTagGuard(List.of(), List.of());

    /** The service's correlation ID recorder. */
    final CorrelationIdRecorder recorder = new CorrelationIdRecorder();

    /**
     * Returns the service's URL filter.
     *
     * @return the filter
     */
    @Bean
    TelemetryUrlFilter serviceTelemetryUrlFilter() {
      return filter;
    }

    /**
     * Returns the service's tag guard.
     *
     * @return the guard
     */
    @Bean
    MetricTagGuard serviceMetricTagGuard() {
      return guard;
    }

    /**
     * Returns the service's correlation ID recorder.
     *
     * @return the recorder
     */
    @Bean
    CorrelationIdRecorder serviceCorrelationIdRecorder() {
      return recorder;
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

  /** A service that declares its customizer as a bean, which Stratum ignores. */
  @Configuration(proxyBeanMethods = false)
  static class CustomizerAsBeanConfiguration {

    /**
     * Returns a customizer that would refuse {@code regionId}.
     *
     * @return the customizer
     */
    @Bean
    StratumPropertiesCustomizer<ObservabilityProperties> regionTagCustomizer() {
      return new RegionTagObservabilitySettings();
    }
  }
}
