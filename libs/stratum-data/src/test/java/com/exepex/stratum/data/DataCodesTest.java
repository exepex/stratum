package com.exepex.stratum.data;

import com.exepex.stratum.test.ErrorCodeChecks;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The data module's codes follow the format and are all documented")
class DataCodesTest {

  @Test
  @DisplayName("every code is SX-DATA- and four digits, unique, and listed on the error codes page")
  void codesFollowFormatAndAreDocumented() throws IOException {
    ErrorCodeChecks.checkErrorCodes(DataConstants.ERROR_CODE_MODULE, DataErrorCode.values());
  }
}
