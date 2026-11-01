package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * Checks a module's error codes against the error codes page, so that the code, the page and Spring
 * Boot's failure report always agree.
 */
@UtilityClass
public class ErrorCodeChecks {

  /** The page that lists every Stratum code, relative to a module's directory. */
  static final Path ERROR_CODES_PAGE = Path.of("../../docs/guides/error-codes.md");

  /** The separator between the cells of a table row. */
  private static final Pattern CELL_SEPARATOR = Pattern.compile(" \\| ");

  /**
   * Checks that every code of a module is {@code SX-<module>-} and four digits, that no two share a
   * number, that the error codes page lists exactly the module's codes, and that the Fix column of
   * each code starts with its {@link ErrorCode#getFix()} text, which Spring Boot's failure report
   * shows.
   *
   * @param module the module's part of the code, such as {@code LOGGING}
   * @param codes every code of the module
   * @throws IOException if the error codes page cannot be read
   */
  public static void checkErrorCodes(String module, ErrorCode... codes) throws IOException {
    var page = Files.readString(ERROR_CODES_PAGE);
    var codeFormat = Pattern.compile("SX-" + module + "-\\d{4}");
    var moduleCodes = Arrays.stream(codes).map(ErrorCode::getCode).toList();
    var documentedCodes =
        codeFormat.matcher(page).results().map(MatchResult::group).distinct().toList();

    assertThat(moduleCodes)
        .allSatisfy(code -> assertThat(code).matches(codeFormat))
        .doesNotHaveDuplicates();
    assertThat(documentedCodes).containsExactlyInAnyOrderElementsOf(moduleCodes);
    var rows = page.lines().toList();
    assertThat(codes)
        .allSatisfy(
            code ->
                assertThat(fixCellOf(rows, code.getCode()))
                    .as("Fix of %s on the error codes page", code.getCode())
                    .startsWith(code.getFix()));
  }

  /** Returns the Fix cell of a code's row without its backticks. */
  private static String fixCellOf(List<String> rows, String code) {
    var row =
        rows.stream()
            .filter(line -> line.startsWith("| `" + code + "` | "))
            .findFirst()
            .orElseThrow(() -> new AssertionError(code + " has no row on the error codes page"));
    var cells = CELL_SEPARATOR.split(row.substring(0, row.lastIndexOf(" |")));
    return cells[cells.length - 1].replace("`", "");
  }
}
