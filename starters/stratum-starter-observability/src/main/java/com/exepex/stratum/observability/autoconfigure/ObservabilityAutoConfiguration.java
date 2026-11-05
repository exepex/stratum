package com.exepex.stratum.observability.autoconfigure;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.core.executor.ContextCarryingExecutorsConfiguration;
import com.exepex.stratum.core.properties.StratumCustomizers;
import com.exepex.stratum.core.reactor.ReactorOperatorContextPropagation;
import com.exepex.stratum.observability.BaggageAllowList;
import com.exepex.stratum.observability.CorrelationIdRecorder;
import com.exepex.stratum.observability.DroppedSpanMeterProvider;
import com.exepex.stratum.observability.ExceptionFreeSpans;
import com.exepex.stratum.observability.JmsBeanObserver;
import com.exepex.stratum.observability.JmsListenerContainerObserver;
import com.exepex.stratum.observability.MeterCountGuard;
import com.exepex.stratum.observability.MetricTagGuard;
import com.exepex.stratum.observability.ObservabilityConstants;
import com.exepex.stratum.observability.ObservabilityProperties;
import com.exepex.stratum.observability.ObservabilityPropertiesCustomizer;
import com.exepex.stratum.observability.TelemetryUrlFilter;
import com.exepex.stratum.observability.linkedwork.LinkedWork;
import com.exepex.stratum.observability.linkedwork.LinkedWorkTracingHandler;
import com.exepex.stratum.observability.linkedwork.ReactiveLinkedWork;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.metrics.MeterProvider;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.core.JmsTemplate;
import reactor.core.publisher.Mono;

/** Creates the observability module's beans, unless {@code stratum.observability.enabled=false}. */
@StratumInternal
@AutoConfiguration(
    after = TaskExecutionAutoConfiguration.class,
    before = TaskSchedulingAutoConfiguration.class)
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(ObservabilityConstants.MODULE)
@Import(ContextCarryingExecutorsConfiguration.class)
public final class ObservabilityAutoConfiguration {

  /**
   * Stops startup with {@code SX-CORE-1004} when the service declares an observability customizer
   * as a bean, which would be silently ignored, and turns on Reactor's context propagation when the
   * service's final setting asks for it.
   *
   * @param beanFactory the service's beans
   * @param environment the service's environment, with its {@code @PropertySource} files loaded
   */
  public ObservabilityAutoConfiguration(ListableBeanFactory beanFactory, Environment environment) {
    StratumCustomizers.rejectBeans(beanFactory);
    ReactorOperatorContextPropagation.enableWhenSetToEveryOperator(environment);
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
   * Caps the number of meters and of HTTP client hosts. Spring Boot applies the bean to every
   * registry, as a {@code MeterFilter} and as a {@code MeterRegistryCustomizer}. The caps come from
   * {@link ObservabilityProperties#resolve}, so the service's customizers win.
   *
   * @param environment the application's environment
   * @return the guard
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  // Last, so a meter another filter drops takes no slot; declared after metricTagGuard because
  // filters of the same order keep the order in which they are declared.
  @Order(Ordered.LOWEST_PRECEDENCE)
  public MeterCountGuard meterCountGuard(Environment environment) {
    var metrics = ObservabilityProperties.resolve(environment).metrics();
    return new MeterCountGuard(
        metrics.maxMeters(),
        metrics.maxClientNames(),
        environment.getProperty(
            ObservabilityConstants.CLIENT_REQUESTS_NAME,
            ObservabilityConstants.DEFAULT_CLIENT_REQUESTS_NAME));
  }

  /**
   * Keeps only the allowed baggage entries in every call and message the service sends or reads.
   * The names come from {@link ObservabilityProperties#resolve} and Spring Boot's remote fields.
   *
   * @param environment the application's environment
   * @return the allow-list
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  // Static, because Spring creates post-processors before other beans: an instance method would
  // create this configuration that early, and its constructor checks the customizer beans.
  public static BaggageAllowList baggageAllowList(Environment environment) {
    return BaggageAllowList.from(environment);
  }

  /**
   * Keeps the exception's type, message and stack trace out of every span the service sends. A
   * service replaces it with a bean of the same name.
   *
   * @return the post-processor
   */
  @Bean(ObservabilityConstants.EXCEPTION_FREE_SPANS)
  @Fallback
  @ConditionalOnMissingBean(name = ObservabilityConstants.EXCEPTION_FREE_SPANS)
  // Static, because Spring creates post-processors before other beans: an instance method would
  // create this configuration that early, and its constructor checks the customizer beans.
  public static ExceptionFreeSpans exceptionFreeSpans() {
    return new ExceptionFreeSpans();
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

  /**
   * Counts the spans OpenTelemetry drops when its export queue is full. Spring Boot gives every
   * {@code MeterProvider} bean to its batch span processor and binds every {@code MeterBinder} bean
   * to the meter registry. A service's own {@code MeterProvider} replaces it.
   *
   * @return the provider
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean(MeterProvider.class)
  public DroppedSpanMeterProvider droppedSpanMeterProvider() {
    return new DroppedSpanMeterProvider();
  }

  /**
   * Starts the span of linked work with links to its origins. Ordered before Spring Boot's tracing
   * handler, which would otherwise make the span a child of the current one without links.
   *
   * @param tracer creates the spans; without one, linked work is timed but not traced
   * @return the handler
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  public LinkedWorkTracingHandler linkedWorkTracingHandler(ObjectProvider<Tracer> tracer) {
    return new LinkedWorkTracingHandler(tracer.getIfAvailable(() -> Tracer.NOOP));
  }

  /**
   * Links work that runs later back to the request that caused it.
   *
   * @param observationRegistry times and traces each run
   * @param tracer reads the request's span and correlation ID
   * @return the linked work
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public LinkedWork linkedWork(
      ObjectProvider<ObservationRegistry> observationRegistry, ObjectProvider<Tracer> tracer) {
    return new LinkedWork(
        observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP),
        tracer.getIfAvailable(() -> Tracer.NOOP));
  }

  /**
   * Runs a {@code Mono} or {@code Flux} as linked work when Reactor is on the classpath. A nested
   * class, so Reactor's types are only loaded then.
   */
  @StratumInternal
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule(ObservabilityConstants.MODULE)
  @ConditionalOnClass(Mono.class)
  public static class ReactiveLinkedWorkConfiguration {

    /**
     * Runs a {@code Mono} or {@code Flux} as linked work.
     *
     * @param linkedWork gives each run its observation
     * @return the reactive linked work
     */
    @Bean
    @Fallback
    @ConditionalOnMissingBean
    public ReactiveLinkedWork reactiveLinkedWork(LinkedWork linkedWork) {
      return new ReactiveLinkedWork(linkedWork);
    }
  }

  /**
   * Times and traces the JMS senders and listeners the service sets up itself, when JMS is on the
   * classpath. A nested class, so JMS types are only loaded then.
   */
  @StratumInternal
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule(ObservabilityConstants.MODULE)
  @ConditionalOnClass(JmsTemplate.class)
  public static class JmsObservationConfiguration {

    /**
     * Gives the observation registry to the JMS beans the service declares. Static, because Spring
     * creates bean post-processors before the other beans.
     *
     * @param observationRegistry the application's registry, read when a JMS bean is found
     * @return the post-processor
     */
    @Bean
    @Fallback
    @ConditionalOnMissingBean
    public static JmsBeanObserver jmsBeanObserver(
        ObjectProvider<ObservationRegistry> observationRegistry) {
      return new JmsBeanObserver(observationRegistry);
    }

    /**
     * Gives the observation registry to the {@code @JmsListener} containers, which are not beans.
     *
     * @param endpointRegistry the registry of the containers, when JMS listeners are set up
     * @param jmsBeanObserver gives each container the registry
     * @return the observer
     */
    @Bean
    @Fallback
    @ConditionalOnMissingBean
    public JmsListenerContainerObserver jmsListenerContainerObserver(
        ObjectProvider<JmsListenerEndpointRegistry> endpointRegistry,
        JmsBeanObserver jmsBeanObserver) {
      return new JmsListenerContainerObserver(endpointRegistry, jmsBeanObserver);
    }
  }
}
