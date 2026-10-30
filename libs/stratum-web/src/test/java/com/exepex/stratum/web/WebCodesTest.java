package com.exepex.stratum.web;

import com.exepex.stratum.test.StarterTestSupport;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The web module's codes follow the format and are all documented")
class WebCodesTest {

  @Test
  @DisplayName("every code is SX-WEB- and four digits, unique, and listed on the error codes page")
  void codesFollowFormatAndAreDocumented() throws IOException {
    StarterTestSupport.checkErrorCodes("WEB", WebErrorCode.values());
  }
}
