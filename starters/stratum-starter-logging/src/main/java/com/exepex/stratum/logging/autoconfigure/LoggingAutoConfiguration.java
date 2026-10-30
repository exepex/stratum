package com.exepex.stratum.logging.autoconfigure;

import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.core.executor.ContextCarryingExecutorsConfiguration;
import com.exepex.stratum.core.properties.StratumCustomizers;
import com.exepex.stratum.core.reactor.ReactorOperatorContextPropagation;
import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.mdc.ServiceMdcFieldsPropagation;
import com.exepex.stratum.logging.mdc.ServletRequestMdcCleanupFilter;
import com.exepex.stratum.logging.mdc.ServletRequestMdcCleanupListener;
import com.exepex.stratum.logging.workflow.ReactiveWorkflows;
import com.exepex.stratum.logging.workflow.WorkflowAspect;
import com.exepex.stratum.logging.workflow.Workflows;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import java.util.EnumSet;
import org.aspectj.weaver.Advice;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingFilterBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import reactor.core.publisher.Mono;

/** Creates the logging module's beans, when {@code stratum.logging.enabled=true}. */
@AutoConfiguration(
    after = TaskExecutionAutoConfiguration.class,
    before = TaskSchedulingAutoConfiguration.class)
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(LoggingConstants.MODULE)
@Import(ContextCarryingExecutorsConfiguration.class)
public final class LoggingAutoConfiguration {

  /**
   * Stops startup with {@code SX-CORE-1004} when the service declares a logging customizer as a
   * bean, which would be silently ignored, and turns on Reactor's context propagation when the
   * service's final setting asks for it.
   *
   * @param beanFactory the service's beans
   * @param environment the service's environment, with its {@code @PropertySource} files loaded
   */
  public LoggingAutoConfiguration(ListableBeanFactory beanFactory, Environment environment) {
    StratumCustomizers.rejectBeans(beanFactory);
    ReactorOperatorContextPropagation.enableWhenSetToEveryOperator(environment);
  }

  /**
   * Carries the MDC fields the service puts itself, such as {@code customerId}, to the threads a
   * request's work moves to, while the module is on.
   *
   * @return the registration, removed when the application stops
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public ServiceMdcFieldsPropagation serviceMdcFieldsPropagation() {
    return new ServiceMdcFieldsPropagation();
  }

  /**
   * Runs workflows; they are timed and traced only when the service has an {@code
   * ObservationRegistry}, as with the observability starter.
   *
   * @param observationRegistry the service's registry, if it has one
   * @return the workflows
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public Workflows workflows(ObjectProvider<ObservationRegistry> observationRegistry) {
    return new Workflows(observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP));
  }

  /**
   * Makes {@code @Workflow} work when AspectJ is on the classpath, the same condition Spring Boot
   * uses to apply aspects. A nested class, so the aspect's AspectJ types are only loaded then.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule(LoggingConstants.MODULE)
  @ConditionalOnClass(Advice.class)
  public static class WorkflowAnnotationConfiguration {

    /**
     * Runs each {@code @Workflow} method as its workflow.
     *
     * @param workflows runs the workflows
     * @param reactiveWorkflows runs the workflows of methods that return a {@code Mono} or {@code
     *     Flux}, when Reactor is on the classpath
     * @return the aspect
     */
    @Bean
    @Fallback
    @ConditionalOnMissingBean
    public WorkflowAspect workflowAspect(
        Workflows workflows, ObjectProvider<ReactiveWorkflows> reactiveWorkflows) {
      return new WorkflowAspect(workflows, reactiveWorkflows.getIfAvailable());
    }
  }

  /**
   * Runs a {@code Mono} or {@code Flux} as a workflow when Reactor is on the classpath. A nested
   * class, so Reactor's types are only loaded then.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule(LoggingConstants.MODULE)
  @ConditionalOnClass(Mono.class)
  public static class ReactiveWorkflowConfiguration {

    /**
     * Runs a {@code Mono} or {@code Flux} as a workflow.
     *
     * @param workflows gives each run its fields and its observation
     * @return the reactive workflows
     */
    @Bean
    @Fallback
    @ConditionalOnMissingBean
    public ReactiveWorkflows reactiveWorkflows(Workflows workflows) {
      return new ReactiveWorkflows(workflows);
    }
  }

  /**
   * Cleans the MDC after each request in a servlet application. A nested class, so the servlet
   * types are only loaded then.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule(LoggingConstants.MODULE)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass(Filter.class)
  public static class ServletRequestMdcCleanupConfiguration {

    /**
     * Runs the cleanup first and ends it last, so it also removes what other filters put, and on
     * async and error dispatches, which run outside the first pass through the filters.
     *
     * @return the filter's registration
     */
    @Bean
    @Fallback
    @ConditionalOnMissingFilterBean(ServletRequestMdcCleanupFilter.class)
    public FilterRegistrationBean<ServletRequestMdcCleanupFilter> servletRequestMdcCleanupFilter() {
      var registration = new FilterRegistrationBean<>(new ServletRequestMdcCleanupFilter());
      registration.setDispatcherTypes(
          EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR));
      registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
      return registration;
    }

    /**
     * Cleans the MDC after a failed request once the server has logged the failure.
     *
     * @return the listener's registration
     */
    @Bean
    @Fallback
    @ConditionalOnMissingBean(
        value = ServletRequestMdcCleanupListener.class,
        parameterizedContainer = ServletListenerRegistrationBean.class)
    public ServletListenerRegistrationBean<ServletRequestMdcCleanupListener>
        servletRequestMdcCleanupListener() {
      return new ServletListenerRegistrationBean<>(new ServletRequestMdcCleanupListener());
    }
  }
}
