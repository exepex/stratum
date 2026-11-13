package com.exepex.stratum.web.autoconfigure;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.properties.BackwardCompatibleMode;
import com.exepex.stratum.core.request.RequestTimeLeft;
import com.exepex.stratum.web.CorrelationIdChooser;
import com.exepex.stratum.web.CurrentRequestTimeLeft;
import com.exepex.stratum.web.RequestBodyLimits;
import com.exepex.stratum.web.RequestDeadlinePropagation;
import com.exepex.stratum.web.RequestFieldsPropagation;
import com.exepex.stratum.web.RequestFieldsReader;
import com.exepex.stratum.web.RequestLogWriter;
import com.exepex.stratum.web.ResponseTimeouts;
import com.exepex.stratum.web.StandardModeReport;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * Creates what both web starters use to give every log line of a request its fields, the request
 * its correlation ID, its one closing log line, its time limit and its body size limit, and the
 * startup line of standard mode. Other threads can read the time limit through {@link
 * RequestTimeLeft}. The web MVC and WebFlux auto-configurations import it.
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
   * @return the registration, kept for the rest of the JVM
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
   * @param environment where {@code stratum.backward-compatible} is read from
   * @return the timeouts
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public ResponseTimeouts responseTimeouts(WebProperties webProperties, Environment environment) {
    return new ResponseTimeouts(webProperties, BackwardCompatibleMode.isOn(environment));
  }

  /**
   * Tells the service how long the request whose work runs on the calling thread has left.
   * Stratum's data module reads it to end statements and connection borrows before the request's
   * limit.
   *
   * @return the time left of the current request
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public RequestTimeLeft requestTimeLeft() {
    return new CurrentRequestTimeLeft();
  }

  /**
   * Carries each request's deadline to the threads its work moves to.
   *
   * @return the registration, kept for the rest of the JVM
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public RequestDeadlinePropagation requestDeadlinePropagation() {
    return new RequestDeadlinePropagation();
  }

  /**
   * Decides how large each request's body may be.
   *
   * @param webProperties the web module's settings
   * @param environment where {@code stratum.backward-compatible} is read from
   * @return the limits
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public RequestBodyLimits requestBodyLimits(WebProperties webProperties, Environment environment) {
    return new RequestBodyLimits(webProperties, BackwardCompatibleMode.isOn(environment));
  }

  /**
   * Logs at startup, outside backward-compatible mode, the default time and body size limits.
   *
   * @param environment where {@code stratum.backward-compatible} is read from
   * @param responseTimeouts the time limits
   * @param requestBodyLimits the body size limits
   * @return the report
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public StandardModeReport standardModeReport(
      Environment environment,
      ResponseTimeouts responseTimeouts,
      RequestBodyLimits requestBodyLimits) {
    return new StandardModeReport(environment, responseTimeouts, requestBodyLimits);
  }
}
