package com.exepex.stratum.webclient;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.test.StarterTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The reactive HTTP client starter follows Stratum's architecture rules")
class WebClientArchTest {

  @Test
  @DisplayName(
      "its autoconfigure package only declares beans, it uses only the shared HTTP client library"
          + " and core, and has no cycles")
  void followsStarterArchitecture() {
    StarterTestSupport.checkStarterArchitecture(
        WebClientConnectionPool.class, HttpClientConstants.class.getPackageName());
  }
}
