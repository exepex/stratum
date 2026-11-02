package com.exepex.stratum.web.autoconfigure;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.web.CorrelationIdChooser;
import com.exepex.stratum.web.RequestBodyLimits;
import com.exepex.stratum.web.RequestFieldsPropagation;
import com.exepex.stratum.web.RequestFieldsReader;
import com.exepex.stratum.web.RequestLogWriter;
import com.exepex.stratum.web.ResponseTimeouts;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.context.annotation.Import;

/**
 * Creates what both web starters use to give every log line of a request its fields, the request
 * its correlation ID, its one closing log line, its time limit and its body size limit. The web MVC
 * and WebFlux auto-configurations import it.
 */
@StratumInternal
@Configuration(proxyBeanMethods = false)
@ConditionalOnStratumModule(WebConstants.MODULE)
@ConditionalOnWebApplication
@Import(WebSettingsConfiguration.class)
public class RequestFieldsConfiguration {

  /**
   * Reads each request's fields.
   *
   * @param webProperties the web module's settings
   * @return the reader
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public RequestFieldsReader requestFieldsReader(WebProperties webProperties) {
    return RequestFieldsReader.from(webProperties);
  }

  /**
   * Chooses each request's correlation ID, when Micrometer Tracing is on the classpath. The ID
   * travels in the tracing baggage, so without tracing there is none.
   *
   * @param webProperties the web module's settings
   * @return the chooser
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  @ConditionalOnClass(name = "io.micrometer.tracing.Tracer")
  public CorrelationIdChooser correlationIdChooser(WebProperties webProperties) {
    return new CorrelationIdChooser(webProperties);
  }

  /**
   * Writes the log line each request ends with.
   *
   * @param webProperties the web module's settings
   * @param requestFieldsReader makes each value safe for the logs
   * @return the writer
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public RequestLogWriter requestLogWriter(
      WebProperties webProperties, RequestFieldsReader requestFieldsReader) {
    return new RequestLogWriter(webProperties, requestFieldsReader);
  }

  /**
   * Carries the request fields to the threads a request's work moves to.
   *
   * @return the registration, removed when the application stops
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public RequestFieldsPropagation requestFieldsPropagation() {
    return new RequestFieldsPropagation();
  }

  /**
   * Decides how long each request may take until its response starts. Its timer thread stops with
   * the application.
   *
   * @param webProperties the web module's settings
   * @return the timeouts
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public ResponseTimeouts responseTimeouts(WebProperties webProperties) {
    return new ResponseTimeouts(webProperties);
  }

  /**
   * Decides how large each request's body may be.
   *
   * @param webProperties the web module's settings
   * @return the limits
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public RequestBodyLimits requestBodyLimits(WebProperties webProperties) {
    return new RequestBodyLimits(webProperties);
  }
}
