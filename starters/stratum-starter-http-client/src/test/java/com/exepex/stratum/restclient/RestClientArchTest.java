package com.exepex.stratum.restclient;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.test.StarterTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The blocking HTTP client starter follows Stratum's architecture rules")
class RestClientArchTest {

  @Test
  @DisplayName(
      "its autoconfigure package only declares beans, it uses only the shared HTTP client library"
          + " and core, and has no cycles")
  void followsStarterArchitecture() {
    StarterTestSupport.checkStarterArchitecture(
        RestClientConnectionPool.class, HttpClientConstants.class.getPackageName());
  }
}
