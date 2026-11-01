package com.exepex.stratum.observability;

import com.exepex.stratum.test.ErrorCodeChecks;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The observability module's codes follow the format and are all documented")
class ObservabilityCodesTest {

  @Test
  @DisplayName(
      "every code is SX-OBSERVABILITY- and four digits, unique, and listed on the error codes page")
  void codesFollowFormatAndAreDocumented() throws IOException {
    ErrorCodeChecks.checkErrorCodes("OBSERVABILITY", ObservabilityErrorCode.values());
  }
}
