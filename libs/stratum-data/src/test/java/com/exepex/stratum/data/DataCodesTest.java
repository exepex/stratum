package com.exepex.stratum.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.core.error.StratumStartupFailureAnalyzer;
import com.exepex.stratum.test.ErrorCodeChecks;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

@DisplayName("The data module's codes")
class DataCodesTest {

  @Nested
  @DisplayName("on the error codes page")
  class Documented {

    @Test
    @DisplayName(
        "every code is SX-DATA- and four digits, unique, and listed on the error codes page")
    void codesFollowFormatAndAreDocumented() throws IOException {
      ErrorCodeChecks.checkErrorCodes(DataConstants.ERROR_CODE_MODULE, DataErrorCode.values());
    }
  }

  @Nested
  @DisplayName("in Spring Boot's failure report")
  class FailureReport {

    /** The codes a caller gets, after startup: one statement or one repository call fails. */
    private static final Set<DataErrorCode> CALLER_CODES =
        Set.of(DataErrorCode.STATEMENT_TIMED_OUT, DataErrorCode.POOL_MIXED);

    /** Every other code, each of which stops startup. */
    private static final List<DataErrorCode> STARTUP_CODES =
        Arrays.stream(DataErrorCode.values()).filter(code -> !CALLER_CODES.contains(code)).toList();

    /** Writes Spring Boot's "APPLICATION FAILED TO START" report for a Stratum startup code. */
    private final StratumStartupFailureAnalyzer analyzer = new StratumStartupFailureAnalyzer();

    @ParameterizedTest(name = "{0}")
    @FieldSource("CALLER_CODES")
    @DisplayName(
        "a code a caller gets is left to Spring Boot, which logs it with the stack trace, even"
            + " when it ends a CommandLineRunner")
    void callerCodeLeftToSpringBoot(DataErrorCode code) {
      assertThat(analyzer.analyze(new StratumException(code))).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("STARTUP_CODES")
    @DisplayName("a code that stops startup gets the report, with its fix as the action")
    void startupCodeReported(DataErrorCode code) {
      var analysis = analyzer.analyze(new StratumException(code));

      assertThat(code.getFix()).isNotEmpty();
      assertThat(analysis).isNotNull();
      assertThat(analysis.getAction()).startsWith(code.getFix() + " See " + code.getCode());
    }
  }
}
