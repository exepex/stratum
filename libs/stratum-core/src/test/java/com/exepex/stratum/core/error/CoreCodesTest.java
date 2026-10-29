package com.exepex.stratum.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("stratum-core's codes follow the format and are all documented")
class CoreCodesTest {

  /** The page that lists every Stratum code, relative to this module's directory. */
  private static final Path ERROR_CODES_PAGE = Path.of("../../docs/guides/error-codes.md");

  /** The format of every stratum-core code. */
  private static final Pattern CORE_CODE = Pattern.compile("SX-CORE-\\d{4}");

  @Test
  @DisplayName("every code is SX-CORE- and four digits, and no two share a number")
  void everyCodeHasTheFormat() {
    assertThat(coreCodes())
        .allSatisfy(code -> assertThat(code).matches(CORE_CODE))
        .doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("the error codes page lists exactly the module's codes")
  void pageListsExactlyTheCodes() throws IOException {
    var documented =
        CORE_CODE
            .matcher(Files.readString(ERROR_CODES_PAGE))
            .results()
            .map(MatchResult::group)
            .distinct()
            .toList();

    assertThat(documented).containsExactlyInAnyOrderElementsOf(coreCodes());
  }

  /** Returns every code of stratum-core. */
  private List<String> coreCodes() {
    return Arrays.stream(CoreErrorCode.values()).map(CoreErrorCode::getCode).toList();
  }
}
