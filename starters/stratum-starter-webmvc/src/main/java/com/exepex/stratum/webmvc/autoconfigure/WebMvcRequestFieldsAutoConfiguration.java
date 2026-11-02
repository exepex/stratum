package com.exepex.stratum.webmvc.autoconfigure;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.web.CorrelationIdChooser;
import com.exepex.stratum.web.RequestBodyLimits;
import com.exepex.stratum.web.RequestFieldsAccessor;
import com.exepex.stratum.web.RequestFieldsReader;
import com.exepex.stratum.web.RequestLogWriter;
import com.exepex.stratum.web.ResponseTimeouts;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.autoconfigure.RequestFieldsConfiguration;
import com.exepex.stratum.webmvc.CorrelationIdBaggageFilter;
import com.exepex.stratum.webmvc.CorrelationIdFilter;
import com.exepex.stratum.webmvc.RequestBodyLimitFilter;
import com.exepex.stratum.webmvc.RequestBodyLimitInterceptor;
import com.exepex.stratum.webmvc.RequestFieldsFilter;
import com.exepex.stratum.webmvc.RequestLogFilter;
import com.exepex.stratum.webmvc.RequestRouteInterceptor;
import com.exepex.stratum.webmvc.ResponseTimeoutFilter;
import com.exepex.stratum.webmvc.ResponseTimeoutInterceptor;
import com.exepex.stratum.webmvc.ServletRequestMdcCleanupFilter;
import com.exepex.stratum.webmvc.ServletRequestMdcCleanupListener;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.context.annotation.Import;

/**
 * Gives every log line of a Spring MVC request its fields, every request its correlation ID, one
 * closing log line and a time limit, and leaves the server thread's logging context clean when the
 * request ends, when {@code stratum.web.enabled=true}.
 */
@StratumInternal
@AutoConfiguration
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(WebConstants.MODULE)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import(RequestFieldsConfiguration.class)
public final class WebMvcRequestFieldsAutoConfiguration {

  /**
   * Puts the logging context back as it was when each request ends, so a field the service put and
   * never removed does not reach the next request on the thread.
   *
   * @return the filter's registration, first in the chain, for the first dispatch and the
   *     asynchronous and error ones, which run outside the first pass through the filters
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean(name = "stratumMdcCleanupFilter")
  public FilterRegistrationBean<ServletRequestMdcCleanupFilter> stratumMdcCleanupFilter() {
    var registration = new FilterRegistrationBean<>(new ServletRequestMdcCleanupFilter());
    registration.setOrder(ServletRequestMdcCleanupFilter.ORDER);
    registration.setDispatcherTypes(
        DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
    return registration;
  }

  /**
   * Cleans the logging context after a failed request once the server has logged the failure.
   *
   * @return the listener's registration
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean(name = "stratumMdcCleanupListener")
  public ServletListenerRegistrationBean<ServletRequestMdcCleanupListener>
      stratumMdcCleanupListener() {
    return new ServletListenerRegistrationBean<>(new ServletRequestMdcCleanupListener());
  }

  /**
   * Puts each request's fields in the logging context for the whole request.
   *
   * @param requestFieldsReader reads the fields
   * @return the filter's registration, just inside the request IDs filter, for the first dispatch
   *     and the asynchronous one that writes a {@code Callable}'s response
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean(name = "stratumRequestFieldsFilter")
  public FilterRegistrationBean<RequestFieldsFilter> stratumRequestFieldsFilter(
      RequestFieldsReader requestFieldsReader) {
    var registration =
        new FilterRegistrationBean<>(
            new RequestFieldsFilter(requestFieldsReader, new RequestFieldsAccessor()));
    registration.setOrder(RequestFieldsFilter.ORDER);
    registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
    return registration;
  }

  /**
   * Writes one log line when each request has been answered.
   *
   * @param requestLogWriter writes the line
   * @return the filter's registration, just inside the request fields filter, for the first
   *     dispatch and the asynchronous one that writes a {@code Callable}'s response
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean(name = "stratumRequestLogFilter")
  public FilterRegistrationBean<RequestLogFilter> stratumRequestLogFilter(
      RequestLogWriter requestLogWriter) {
    var registration = new FilterRegistrationBean<>(new RequestLogFilter(requestLogWriter));
    registration.setOrder(RequestLogFilter.ORDER);
    registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
    return registration;
  }

  /**
   * Answers a request that ran out of time with the timeout status.
   *
   * @param responseTimeouts decides each request's limit
   * @return the filter's registration, just inside the request log filter, for the first dispatch
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean(name = "stratumResponseTimeoutFilter")
  public FilterRegistrationBean<ResponseTimeoutFilter> stratumResponseTimeoutFilter(
      ResponseTimeouts responseTimeouts) {
    var registration = new FilterRegistrationBean<>(new ResponseTimeoutFilter(responseTimeouts));
    registration.setOrder(ResponseTimeoutFilter.ORDER);
    return registration;
  }

  /**
   * Gives each request the limit of its handler's {@code @ResponseTimeout}.
   *
   * @param responseTimeouts reads the handler's limit
   * @return the interceptor
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public ResponseTimeoutInterceptor responseTimeoutInterceptor(ResponseTimeouts responseTimeouts) {
    return new ResponseTimeoutInterceptor(responseTimeouts);
  }

  /**
   * Answers a request whose body is larger than its limit with {@code 413}.
   *
   * @param requestBodyLimits decides each request's limit
   * @return the filter's registration, just inside the response timeout filter
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean(name = "stratumRequestBodyLimitFilter")
  public FilterRegistrationBean<RequestBodyLimitFilter> stratumRequestBodyLimitFilter(
      RequestBodyLimits requestBodyLimits) {
    var registration = new FilterRegistrationBean<>(new RequestBodyLimitFilter(requestBodyLimits));
    registration.setOrder(RequestBodyLimitFilter.ORDER);
    return registration;
  }

  /**
   * Gives each request the limit of its handler's {@code @RequestBodyLimit}, and checks the
   * declared length before the handler reads the body.
   *
   * @param requestBodyLimits reads the handler's limit
   * @return the interceptor
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public RequestBodyLimitInterceptor requestBodyLimitInterceptor(
      RequestBodyLimits requestBodyLimits) {
    return new RequestBodyLimitInterceptor(requestBodyLimits);
  }

  /**
   * Adds the matched route to the logging context.
   *
   * @return the interceptor
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public RequestRouteInterceptor requestRouteInterceptor() {
    return new RequestRouteInterceptor();
  }

  /**
   * Gives each request its correlation ID when Micrometer Tracing is on the classpath, as the
   * observability starter brings it. A nested class, so Tracing's types are only loaded then.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule(WebConstants.MODULE)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass(Tracer.class)
  public static class CorrelationIdConfiguration {

    /**
     * Returns each request's correlation ID in the response header.
     *
     * @param tracer reads the baggage and the trace ID
     * @param correlationIdChooser reads the ID
     * @return the filter's registration, just inside Spring Boot's tracing filter
     */
    @Bean
    @Fallback
    @ConditionalOnMissingBean(name = "stratumCorrelationIdFilter")
    public FilterRegistrationBean<CorrelationIdFilter> stratumCorrelationIdFilter(
        ObjectProvider<Tracer> tracer, CorrelationIdChooser correlationIdChooser) {
      var registration =
          new FilterRegistrationBean<>(
              new CorrelationIdFilter(
                  tracer.getIfAvailable(() -> Tracer.NOOP), correlationIdChooser));
      registration.setOrder(CorrelationIdFilter.ORDER);
      return registration;
    }

    /**
     * Writes a caller's correlation ID into each request's baggage before tracing reads it.
     *
     * @param correlationIdChooser chooses the ID
     * @return the filter's registration, before Spring Boot's tracing filter
     */
    @Bean
    @Fallback
    @ConditionalOnMissingBean(name = "stratumCorrelationIdBaggageFilter")
    public FilterRegistrationBean<CorrelationIdBaggageFilter> stratumCorrelationIdBaggageFilter(
        CorrelationIdChooser correlationIdChooser) {
      var registration =
          new FilterRegistrationBean<>(new CorrelationIdBaggageFilter(correlationIdChooser));
      registration.setOrder(CorrelationIdBaggageFilter.ORDER);
      return registration;
    }
  }
}
