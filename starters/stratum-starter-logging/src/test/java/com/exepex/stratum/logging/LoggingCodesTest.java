package com.exepex.stratum.logging;

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

@DisplayName("The logging module's codes follow the format and are all documented")
class LoggingCodesTest {

  /** The page that lists every Stratum code, relative to this module's directory. */
  private static final Path ERROR_CODES_PAGE = Path.of("../../docs/guides/error-codes.md");

  /** The format of every logging code. */
  private static final Pattern LOGGING_CODE = Pattern.compile("SX-LOGGING-\\d{4}");

  @Test
  @DisplayName("every code is SX-LOGGING- and four digits, and no two share a number")
  void everyCodeHasTheFormat() {
    assertThat(loggingCodes())
        .allSatisfy(code -> assertThat(code).matches(LOGGING_CODE))
        .doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("the error codes page lists exactly the module's codes")
  void pageListsExactlyTheCodes() throws IOException {
    var documented =
        LOGGING_CODE
            .matcher(Files.readString(ERROR_CODES_PAGE))
            .results()
            .map(MatchResult::group)
            .distinct()
            .toList();

    assertThat(documented).containsExactlyInAnyOrderElementsOf(loggingCodes());
  }

  /** Returns every code of the logging module. */
  private List<String> loggingCodes() {
    return Arrays.stream(LoggingErrorCode.values()).map(LoggingErrorCode::getCode).toList();
  }
}
