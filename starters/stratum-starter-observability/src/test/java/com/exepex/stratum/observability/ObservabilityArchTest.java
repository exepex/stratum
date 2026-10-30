package com.exepex.stratum.observability;

import com.exepex.stratum.test.StarterTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The observability starter follows Stratum's architecture rules")
class ObservabilityArchTest {

  @Test
  @DisplayName(
      "its autoconfigure package only declares beans, it uses no other starter and has no cycles")
  void followsStarterArchitecture() {
    StarterTestSupport.checkStarterArchitecture(ObservabilityConstants.class);
  }
}
