package com.exepex.stratum.webmvc;

import com.exepex.stratum.test.StarterTestSupport;
import com.exepex.stratum.web.WebConstants;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The web MVC starter follows Stratum's architecture rules")
class WebMvcArchTest {

  /**
   * The web MVC starter's public types that services may use; every other one carries the label.
   */
  private static final Set<Class<?>> API_TYPES =
      Set.of(
          ErrorBodyAdvice.class,
          FailureWithErrorCodeResolver.class,
          RequestBodyLimitInterceptor.class,
          RequestRouteInterceptor.class,
          ResponseTimeoutInterceptor.class);

  @Test
  @DisplayName(
      "its autoconfigure package only declares beans, it uses only the shared web library and"
          + " core, and has no cycles")
  void followsStarterArchitecture() {
    StarterTestSupport.checkStarterArchitecture(
        FailureWithErrorCodeResolver.class, API_TYPES, WebConstants.class.getPackageName());
  }
}
