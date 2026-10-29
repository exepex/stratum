package com.exepex.stratum.observability.autoconfigure;

import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.core.properties.StratumCustomizers;
import com.exepex.stratum.observability.CorrelationIdRecorder;
import com.exepex.stratum.observability.MetricTagGuard;
import com.exepex.stratum.observability.ObservabilityConstants;
import com.exepex.stratum.observability.ObservabilityProperties;
import com.exepex.stratum.observability.ObservabilityPropertiesCustomizer;
import com.exepex.stratum.observability.TelemetryUrlFilter;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Fallback;
import org.springframework.core.env.Environment;

/** Creates the observability module's beans, when {@code stratum.observability.enabled=true}. */
@AutoConfiguration
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(ObservabilityConstants.MODULE)
public final class ObservabilityAutoConfiguration {

  /**
   * Stops startup with {@code SX-CORE-1004} when the service declares an observability customizer
   * as a bean, which would be silently ignored.
   *
   * @param beanFactory the service's beans
   */
  public ObservabilityAutoConfiguration(ListableBeanFactory beanFactory) {
    StratumCustomizers.rejectBeans(beanFactory);
  }

  /**
   * Removes user info from span URLs and the query from the uri tag of spans and metrics. Spring
   * Boot applies every {@code ObservationFilter} bean to its observations and every {@code
   * MeterFilter} bean to its meters. Without Spring's web classes the service makes no HTTP client
   * spans or metrics, so the filter is not needed.
   *
   * @return the filter
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  @ConditionalOnClass(name = ObservabilityConstants.URI_COMPONENTS_BUILDER)
  public TelemetryUrlFilter telemetryUrlFilter() {
    return new TelemetryUrlFilter();
  }

  /**
   * Drops metrics tagged with an ID. Spring Boot applies every {@code MeterFilter} bean to its
   * meters. The tag names come from {@link ObservabilityProperties#resolve}, so the service's
   * {@link ObservabilityPropertiesCustomizer} classes win.
   *
   * @param environment the application's environment
   * @return the guard
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public MetricTagGuard metricTagGuard(Environment environment) {
    var tags = ObservabilityProperties.resolve(environment).tags();
    return new MetricTagGuard(tags.extraForbidden(), tags.allowed());
  }

  /**
   * Puts the correlation ID in the logging context and on spans. Spring Boot passes every tracing
   * {@code EventListener} and {@code SpanProcessor} bean to OpenTelemetry.
   *
   * @return the recorder
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public CorrelationIdRecorder correlationIdRecorder() {
    return new CorrelationIdRecorder();
  }
}
