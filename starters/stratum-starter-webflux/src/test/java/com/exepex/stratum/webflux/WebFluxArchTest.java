package com.exepex.stratum.webflux;

import com.exepex.stratum.test.StarterTestSupport;
import com.exepex.stratum.web.WebConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The WebFlux starter follows Stratum's architecture rules")
class WebFluxArchTest {

  @Test
  @DisplayName(
      "its autoconfigure package only declares beans, it uses only the shared web library and"
          + " core, and has no cycles")
  void followsStarterArchitecture() {
    StarterTestSupport.checkStarterArchitecture(
        StratumErrorAttributes.class, WebConstants.class.getPackageName());
  }
}
