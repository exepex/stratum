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
  @DisplayName("every code is SX-CORE- and four digits")
  void everyCodeHasTheFormat() {
    assertThat(coreCodes()).allSatisfy(code -> assertThat(code).matches(CORE_CODE));
  }

  @Test
  @DisplayName("no two codes share a number")
  void codesAreUnique() {
    assertThat(coreCodes()).doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("every code is listed on the error codes page")
  void everyCodeIsDocumented() throws IOException {
    assertThat(documentedCodes()).containsAll(coreCodes());
  }

  @Test
  @DisplayName("the error codes page lists no stratum-core code that does not exist")
  void pageListsNoUnknownCode() throws IOException {
    assertThat(coreCodes()).containsAll(documentedCodes());
  }

  /** Returns every stratum-core code. */
  private List<String> coreCodes() {
    return Arrays.stream(CoreErrorCode.values()).map(CoreErrorCode::getCode).toList();
  }

  /** Returns every stratum-core code that the error codes page mentions. */
  private List<String> documentedCodes() throws IOException {
    return CORE_CODE
        .matcher(Files.readString(ERROR_CODES_PAGE))
        .results()
        .map(MatchResult::group)
        .distinct()
        .toList();
  }
}
