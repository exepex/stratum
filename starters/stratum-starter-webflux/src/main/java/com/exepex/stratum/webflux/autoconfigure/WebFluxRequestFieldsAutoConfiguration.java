package com.exepex.stratum.webflux.autoconfigure;

import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.web.CorrelationIdChooser;
import com.exepex.stratum.web.RequestFieldsReader;
import com.exepex.stratum.web.RequestLogWriter;
import com.exepex.stratum.web.ResponseTimeouts;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.autoconfigure.RequestFieldsConfiguration;
import com.exepex.stratum.webflux.CorrelationIdBaggageDecorator;
import com.exepex.stratum.webflux.CorrelationIdWebFilter;
import com.exepex.stratum.webflux.HandlerStartAdapter;
import com.exepex.stratum.webflux.HandlerStartListener;
import com.exepex.stratum.webflux.RequestFieldsWebFilter;
import com.exepex.stratum.webflux.RequestLogWebFilter;
import com.exepex.stratum.webflux.RequestRouteListener;
import com.exepex.stratum.webflux.ResponseTimeoutWebFilter;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.context.annotation.Import;
import org.springframework.web.reactive.HandlerAdapter;

/**
 * Gives every log line of a reactive request its fields, every request its correlation ID, one
 * closing log line and a time limit, when {@code stratum.web.enabled=true}.
 */
@AutoConfiguration
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(WebConstants.MODULE)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@Import(RequestFieldsConfiguration.class)
public final class WebFluxRequestFieldsAutoConfiguration {

  /**
   * Puts each request's fields in Reactor's context for the whole request.
   *
   * @param requestFieldsReader reads the fields
   * @return the filter
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public RequestFieldsWebFilter requestFieldsWebFilter(RequestFieldsReader requestFieldsReader) {
    return new RequestFieldsWebFilter(requestFieldsReader);
  }

  /**
   * Writes one log line when each request's response starts.
   *
   * @param requestLogWriter writes the line
   * @return the filter
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public RequestLogWebFilter requestLogWebFilter(RequestLogWriter requestLogWriter) {
    return new RequestLogWebFilter(requestLogWriter);
  }

  /**
   * Answers a request that ran out of time with the timeout status, and gives each request the
   * limit of its handler's {@code @ResponseTimeout}.
   *
   * @param responseTimeouts decides each request's limit
   * @return the filter
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public ResponseTimeoutWebFilter responseTimeoutWebFilter(ResponseTimeouts responseTimeouts) {
    return new ResponseTimeoutWebFilter(responseTimeouts);
  }

  /**
   * Adds the matched route to the logging context before the handler runs.
   *
   * @return the listener
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public RequestRouteListener requestRouteListener() {
    return new RequestRouteListener();
  }

  /**
   * Tells the listeners, such as the route and the timeout, that a handler is about to run.
   *
   * @param handlerAdapters Spring WebFlux's adapters, which call the handlers
   * @param handlerStartListeners what is told
   * @return the adapter
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public HandlerStartAdapter handlerStartAdapter(
      ObjectProvider<HandlerAdapter> handlerAdapters,
      ObjectProvider<HandlerStartListener> handlerStartListeners) {
    return new HandlerStartAdapter(handlerAdapters, handlerStartListeners);
  }

  /**
   * Gives each request its correlation ID when Micrometer Tracing is on the classpath, as the
   * observability starter brings it. A nested class, so Tracing's types are only loaded then.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule(WebConstants.MODULE)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  @ConditionalOnClass(Tracer.class)
  public static class CorrelationIdConfiguration {

    /**
     * Chooses each request's correlation ID and returns it in the response header.
     *
     * @param tracer reads the baggage; without one, only the caller's header is used
     * @param correlationIdChooser chooses the ID
     * @return the filter
     */
    @Bean
    @Fallback
    @ConditionalOnMissingBean
    public CorrelationIdWebFilter correlationIdWebFilter(
        ObjectProvider<Tracer> tracer, CorrelationIdChooser correlationIdChooser) {
      return new CorrelationIdWebFilter(
          tracer.getIfAvailable(() -> Tracer.NOOP), correlationIdChooser);
    }

    /**
     * Writes a caller's correlation ID into each request's baggage before tracing reads it.
     *
     * @param correlationIdChooser chooses the ID
     * @return the decorator of Spring WebFlux's handler
     */
    @Bean
    @Fallback
    @ConditionalOnMissingBean
    public CorrelationIdBaggageDecorator correlationIdBaggageDecorator(
        CorrelationIdChooser correlationIdChooser) {
      return new CorrelationIdBaggageDecorator(correlationIdChooser);
    }
  }
}
