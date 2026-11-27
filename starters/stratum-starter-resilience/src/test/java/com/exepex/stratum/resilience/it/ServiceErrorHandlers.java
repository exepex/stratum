package com.exepex.stratum.resilience.it;

import com.exepex.stratum.web.WebProperties;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;

/**
 * A service's own error handling, each part switched on by a test property. The handlers are
 * top-level classes: a handler nested in this class would be registered whether switched on or not.
 */
@Configuration(proxyBeanMethods = false)
class ServiceErrorHandlers {

  /** The property that adds the service's own {@code ResponseEntityExceptionHandler}. */
  static final String SPRING_HANDLER = "test.service.spring-handler";

  /** The property that adds the service's catch-all {@code @ExceptionHandler}. */
  static final String CATCH_ALL = "test.service.catch-all";

  /** The property that replaces the web settings with ones that map no status. */
  static final String NO_STATUSES = "test.service.no-statuses";

  /** The status the catch-all handler answers every failure with. */
  static final HttpStatus CATCH_ALL_STATUS = HttpStatus.I_AM_A_TEAPOT;

  /**
   * Returns the service's subclass of Spring's Problem Details handler.
   *
   * @return the handler
   */
  @Bean
  @ConditionalOnProperty(SPRING_HANDLER)
  OrdersProblemHandler ordersProblemHandler() {
    return new OrdersProblemHandler();
  }

  /**
   * Returns the service's catch-all handler.
   *
   * @return the handler
   */
  @Bean
  @ConditionalOnProperty(CATCH_ALL)
  CatchAllHandler catchAllHandler() {
    return new CatchAllHandler();
  }

  /**
   * Returns web settings whose statuses map no code, as a Java customizer that replaces the map
   * gives.
   *
   * @param environment the service's environment
   * @return the settings
   */
  @Bean
  @ConditionalOnProperty(NO_STATUSES)
  WebProperties webPropertiesWithoutStatuses(Environment environment) {
    return WebProperties.resolve(environment).toBuilder()
        .error(WebProperties.ErrorSettings.builder().statuses(Map.of()).build())
        .build();
  }
}
