package com.exepex.stratum.webflux;

import com.exepex.stratum.test.StarterTestSupport;
import com.exepex.stratum.web.WebConstants;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The WebFlux starter follows Stratum's architecture rules")
class WebFluxArchTest {

  /**
   * The WebFlux starter's public types that services may use; every other one carries the label.
   */
  private static final Set<Class<?>> API_TYPES =
      Set.of(
          CorrelationIdBaggageDecorator.class,
          CorrelationIdWebFilter.class,
          HandlerStartAdapter.class,
          HandlerStartListener.class,
          ProblemDetailIdsInterceptor.class,
          RequestBodyLimitWebFilter.class,
          RequestFieldsWebFilter.class,
          RequestLogWebFilter.class,
          RequestRouteListener.class,
          ResponseTimeoutWebFilter.class);

  @Test
  @DisplayName(
      "its autoconfigure package only declares beans, it uses only the shared web library and"
          + " core, and has no cycles")
  void followsStarterArchitecture() {
    StarterTestSupport.checkStarterArchitecture(
        StratumErrorAttributes.class, API_TYPES, WebConstants.class.getPackageName());
  }
}
