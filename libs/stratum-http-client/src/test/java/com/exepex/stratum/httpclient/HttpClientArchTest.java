package com.exepex.stratum.httpclient;

import com.exepex.stratum.test.StarterTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The shared HTTP client library follows Stratum's architecture rules")
class HttpClientArchTest {

  @Test
  @DisplayName("every class a component scan picks up carries the module switch")
  void safeToScan() {
    StarterTestSupport.checkSafeToScan(HttpClientConstants.class);
  }
}
