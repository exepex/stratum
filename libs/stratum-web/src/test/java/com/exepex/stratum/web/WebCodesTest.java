package com.exepex.stratum.web;

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

@DisplayName("The web module's codes follow the format and are all documented")
class WebCodesTest {

  /** The page that lists every Stratum code, relative to this module's directory. */
  private static final Path ERROR_CODES_PAGE = Path.of("../../docs/guides/error-codes.md");

  /** The format of every web code. */
  private static final Pattern WEB_CODE = Pattern.compile("SX-WEB-\\d{4}");

  @Test
  @DisplayName("every code is SX-WEB- and four digits, and no two share a number")
  void everyCodeHasTheFormat() {
    assertThat(webCodes())
        .allSatisfy(code -> assertThat(code).matches(WEB_CODE))
        .doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("the error codes page lists exactly the module's codes")
  void pageListsExactlyTheCodes() throws IOException {
    var documented =
        WEB_CODE
            .matcher(Files.readString(ERROR_CODES_PAGE))
            .results()
            .map(MatchResult::group)
            .distinct()
            .toList();

    assertThat(documented).containsExactlyInAnyOrderElementsOf(webCodes());
  }

  /** Returns every code of the web module. */
  private List<String> webCodes() {
    return Arrays.stream(WebErrorCode.values()).map(WebErrorCode::getCode).toList();
  }
}
