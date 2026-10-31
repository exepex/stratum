package com.exepex.stratum.web.autoconfigure;

import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.web.CorrelationIdChooser;
import com.exepex.stratum.web.RequestFieldsPropagation;
import com.exepex.stratum.web.RequestFieldsReader;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.core.env.Environment;

/**
 * Creates what both web starters use to give every log line of a request its fields and the request
 * its correlation ID. The web MVC and WebFlux auto-configurations import it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnStratumModule(WebConstants.MODULE)
@ConditionalOnWebApplication
public class RequestFieldsConfiguration {

  /**
   * Reads each request's fields. Its settings come from {@link WebProperties#resolve}, so the
   * service's Java configuration wins.
   *
   * @param environment the application's environment
   * @return the reader
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public RequestFieldsReader requestFieldsReader(Environment environment) {
    return RequestFieldsReader.from(WebProperties.resolve(environment));
  }

  /**
   * Chooses each request's correlation ID.
   *
   * @param environment the application's environment
   * @return the chooser
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public CorrelationIdChooser correlationIdChooser(Environment environment) {
    return new CorrelationIdChooser(WebProperties.resolve(environment));
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
}
