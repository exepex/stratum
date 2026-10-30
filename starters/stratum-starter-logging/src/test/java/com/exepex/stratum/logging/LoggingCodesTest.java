package com.exepex.stratum.logging;

import com.exepex.stratum.test.StarterTestSupport;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The logging module's codes follow the format and are all documented")
class LoggingCodesTest {

  @Test
  @DisplayName(
      "every code is SX-LOGGING- and four digits, unique, and listed on the error codes page")
  void codesFollowFormatAndAreDocumented() throws IOException {
    StarterTestSupport.checkErrorCodes("LOGGING", LoggingErrorCode.values());
  }
}
