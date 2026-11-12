package com.exepex.stratum.httpclient;

import com.exepex.stratum.test.ErrorCodeChecks;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The HTTP client module's codes follow the format and are all documented")
class HttpClientCodesTest {

  @Test
  @DisplayName(
      "every code is SX-HTTPC- and four digits, unique, and listed on the error codes page")
  void codesFollowFormatAndAreDocumented() throws IOException {
    ErrorCodeChecks.checkErrorCodes(
        HttpClientConstants.ERROR_CODE_MODULE, HttpClientErrorCode.values());
  }
}
