package com.exepex.stratum.web;

import com.exepex.stratum.test.StarterTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The shared web library follows Stratum's architecture rules")
class WebArchTest {

  @Test
  @DisplayName("every class a component scan picks up carries the module switch")
  void safeToScan() {
    StarterTestSupport.checkSafeToScan(WebConstants.class);
  }
}
