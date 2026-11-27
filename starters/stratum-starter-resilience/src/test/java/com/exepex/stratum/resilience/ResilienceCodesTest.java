package com.exepex.stratum.resilience;

import com.exepex.stratum.test.ErrorCodeChecks;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The resilience module's codes follow the format and are all documented")
class ResilienceCodesTest {

  @Test
  @DisplayName(
      "every code is SX-RESILIENCE- and four digits, unique, and listed on the error codes page")
  void codesFollowFormatAndAreDocumented() throws IOException {
    ErrorCodeChecks.checkErrorCodes(
        ResilienceConstants.ERROR_CODE_MODULE, ResilienceErrorCode.values());
  }
}
