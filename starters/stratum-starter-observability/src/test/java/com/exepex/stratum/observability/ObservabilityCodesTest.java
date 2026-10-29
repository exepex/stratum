package com.exepex.stratum.observability;

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

@DisplayName("The observability module's codes follow the format and are all documented")
class ObservabilityCodesTest {

  /** The page that lists every Stratum code, relative to this module's directory. */
  private static final Path ERROR_CODES_PAGE = Path.of("../../docs/guides/error-codes.md");

  /** The format of every observability code. */
  private static final Pattern OBSERVABILITY_CODE = Pattern.compile("SX-OBSERVABILITY-\\d{4}");

  @Test
  @DisplayName("every code is SX-OBSERVABILITY- and four digits, and no two share a number")
  void everyCodeHasTheFormat() {
    assertThat(observabilityCodes())
        .allSatisfy(code -> assertThat(code).matches(OBSERVABILITY_CODE))
        .doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("the error codes page lists exactly the module's codes")
  void pageListsExactlyTheCodes() throws IOException {
    var documented =
        OBSERVABILITY_CODE
            .matcher(Files.readString(ERROR_CODES_PAGE))
            .results()
            .map(MatchResult::group)
            .distinct()
            .toList();

    assertThat(documented).containsExactlyInAnyOrderElementsOf(observabilityCodes());
  }

  /** Returns every code of the observability module. */
  private List<String> observabilityCodes() {
    return Arrays.stream(ObservabilityErrorCode.values())
        .map(ObservabilityErrorCode::getCode)
        .toList();
  }
}
