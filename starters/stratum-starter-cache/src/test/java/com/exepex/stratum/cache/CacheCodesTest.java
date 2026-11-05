package com.exepex.stratum.cache;

import com.exepex.stratum.test.ErrorCodeChecks;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The cache module's codes follow the format and are all documented")
class CacheCodesTest {

  @Test
  @DisplayName(
      "every code is SX-CACHE- and four digits, unique, and listed on the error codes page")
  void codesFollowFormatAndAreDocumented() throws IOException {
    ErrorCodeChecks.checkErrorCodes(CacheConstants.ERROR_CODE_MODULE, CacheErrorCode.values());
  }
}
