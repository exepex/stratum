package com.exepex.stratum.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.observability.CorrelationIdRecorder;
import com.exepex.stratum.observability.DroppedSpanMeterProvider;
import com.exepex.stratum.observability.HealthProbePaths;
import com.exepex.stratum.observability.HealthProbeTracingHandler;
import com.exepex.stratum.observability.MetricTagGuard;
import com.exepex.stratum.observability.TelemetryUrlFilter;
import com.exepex.stratum.observability.testsupport.ObservabilityTestData;
import io.opentelemetry.api.metrics.MeterProvider;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("ObservabilityAutoConfiguration backs off when the service or a company has its beans")
class ObservabilityAutoConfigurationBackOffTest {

  /** Returns a health-probe handler, as a service or a company would declare its own. */
  private static HealthProbeTracingHandler ownProbeHandler() {
    return new HealthProbeTracingHandler(new HealthProbePaths(new MockEnvironment()));
  }

  @Test
  @DisplayName("the service's own beans replace Stratum's")
  void serviceBeansWin() {
    var serviceFilter = new TelemetryUrlFilter();
    var serviceGuard = new MetricTagGuard(List.of(), List.of());
    MeterProvider serviceMeterProvider = MeterProvider.noop()::meterBuilder;
    var serviceProbeHandler = ownProbeHandler();
    // The recorder is a span processor, which is closeable; the context closes it too.
    try (var serviceRecorder = new CorrelationIdRecorder()) {
      ObservabilityTestData.contextRunner()
          .withBean(TelemetryUrlFilter.class, () -> serviceFilter)
          .withBean(MetricTagGuard.class, () -> serviceGuard)
          .withBean(CorrelationIdRecorder.class, () -> serviceRecorder)
          .withBean(MeterProvider.class, () -> serviceMeterProvider)
          .withBean(HealthProbeTracingHandler.class, () -> serviceProbeHandler)
          .run(
              context ->
                  assertUsesBeans(
                      context,
                      serviceFilter,
                      serviceGuard,
                      serviceRecorder,
                      serviceMeterProvider,
                      serviceProbeHandler));
    }
  }

  @Test
  @DisplayName("a company framework's auto-configuration with no order wins over Stratum's")
  void companyBeansWin() {
    ObservabilityTestData.contextRunner()
        .withConfiguration(AutoConfigurations.of(CompanyBeansAutoConfiguration.class))
        .run(
            context ->
                assertUsesBeans(
                    context,
                    CompanyBeansAutoConfiguration.FILTER,
                    CompanyBeansAutoConfiguration.GUARD,
                    CompanyBeansAutoConfiguration.RECORDER,
                    CompanyBeansAutoConfiguration.METER_PROVIDER,
                    CompanyBeansAutoConfiguration.PROBE_HANDLER));
  }

  @Test
  @DisplayName("company beans made after Stratum's are the ones Spring Boot injects")
  void laterCompanyBeansWin() {
    ObservabilityTestData.contextRunner()
        .withConfiguration(AutoConfigurations.of(LaterCompanyAutoConfiguration.class))
        .run(
            context -> {
              assertThat(context.getBean(MeterProvider.class))
                  .isSameAs(LaterCompanyAutoConfiguration.METER_PROVIDER);
              assertThat(context.getBean(HealthProbeTracingHandler.class))
                  .isSameAs(LaterCompanyAutoConfiguration.PROBE_HANDLER);
            });
  }

  /** Checks that the context holds the given beans instead of Stratum's. */
  private void assertUsesBeans(
      AssertableApplicationContext context,
      TelemetryUrlFilter filter,
      MetricTagGuard guard,
      CorrelationIdRecorder recorder,
      MeterProvider meterProvider,
      HealthProbeTracingHandler probeHandler) {
    assertThat(context.getBean(TelemetryUrlFilter.class)).isSameAs(filter);
    assertThat(context.getBean(MetricTagGuard.class)).isSameAs(guard);
    assertThat(context.getBean(CorrelationIdRecorder.class)).isSameAs(recorder);
    assertThat(context.getBean(MeterProvider.class)).isSameAs(meterProvider);
    assertThat(context.getBean(HealthProbeTracingHandler.class)).isSameAs(probeHandler);
    // The provider is also the binder of the dropped-span counter: without it, no counter.
    assertThat(context).doesNotHaveBean(DroppedSpanMeterProvider.class);
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

    /** The company's OpenTelemetry meter provider. */
    static final MeterProvider METER_PROVIDER = MeterProvider.noop()::meterBuilder;

    /** The company's health-probe handler. */
    static final HealthProbeTracingHandler PROBE_HANDLER = ownProbeHandler();

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

    /**
     * Returns the company's OpenTelemetry meter provider.
     *
     * @return the provider
     */
    @Bean
    MeterProvider companyMeterProvider() {
      return METER_PROVIDER;
    }

    /**
     * Returns the company's health-probe handler.
     *
     * @return the handler
     */
    @Bean
    HealthProbeTracingHandler companyProbeHandler() {
      return PROBE_HANDLER;
    }
  }

  /** A company framework's auto-configuration ordered after Stratum's, so both beans exist. */
  @AutoConfiguration(after = ObservabilityAutoConfiguration.class)
  static class LaterCompanyAutoConfiguration {

    /** The company's OpenTelemetry meter provider. */
    static final MeterProvider METER_PROVIDER = MeterProvider.noop()::meterBuilder;

    /** The company's health-probe handler. */
    static final HealthProbeTracingHandler PROBE_HANDLER = ownProbeHandler();

    /**
     * Returns the company's OpenTelemetry meter provider.
     *
     * @return the provider
     */
    @Bean
    MeterProvider laterCompanyMeterProvider() {
      return METER_PROVIDER;
    }

    /**
     * Returns the company's health-probe handler.
     *
     * @return the handler
     */
    @Bean
    HealthProbeTracingHandler laterCompanyProbeHandler() {
      return PROBE_HANDLER;
    }
  }
}
