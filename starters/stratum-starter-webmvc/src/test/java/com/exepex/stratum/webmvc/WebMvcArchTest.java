package com.exepex.stratum.webmvc;

import com.exepex.stratum.test.StarterTestSupport;
import com.exepex.stratum.web.WebConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The web MVC starter follows Stratum's architecture rules")
class WebMvcArchTest {

  @Test
  @DisplayName(
      "its autoconfigure package only declares beans, it uses only the shared web library and"
          + " core, and has no cycles")
  void followsStarterArchitecture() {
    StarterTestSupport.checkStarterArchitecture(
        StratumExceptionHandler.class, WebConstants.class.getPackageName());
  }
}
