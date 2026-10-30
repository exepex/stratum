package com.exepex.stratum.logging;

import com.exepex.stratum.test.StarterTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The logging starter follows Stratum's architecture rules")
class LoggingArchTest {

  @Test
  @DisplayName(
      "its autoconfigure package only declares beans, it uses no other starter and has no cycles")
  void followsStarterArchitecture() {
    StarterTestSupport.checkStarterArchitecture(LoggingConstants.class);
  }
}
