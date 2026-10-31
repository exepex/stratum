package com.exepex.stratum.webmvc.autoconfigure;

import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.web.CorrelationIdChooser;
import com.exepex.stratum.web.RequestFieldsAccessor;
import com.exepex.stratum.web.RequestFieldsReader;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.autoconfigure.RequestFieldsConfiguration;
import com.exepex.stratum.webmvc.CorrelationIdFilter;
import com.exepex.stratum.webmvc.RequestFieldsFilter;
import com.exepex.stratum.webmvc.RequestRouteInterceptor;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.context.annotation.Import;

/**
 * Gives every log line of a Spring MVC request its fields, and every request its correlation ID,
 * when {@code stratum.web.enabled=true}.
 */
@AutoConfiguration
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(WebConstants.MODULE)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import(RequestFieldsConfiguration.class)
public final class WebMvcRequestFieldsAutoConfiguration {

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
     * Chooses each request's correlation ID and returns it in the response header.
     *
     * @param tracer reads and sets the baggage; without one, only the caller's header is used
     * @param correlationIdChooser chooses the ID
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
  }
}
