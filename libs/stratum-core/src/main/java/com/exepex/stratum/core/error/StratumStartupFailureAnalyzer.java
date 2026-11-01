package com.exepex.stratum.core.error;

import java.util.Objects;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;

/**
 * Turns a Stratum error that stops startup into Spring Boot's "APPLICATION FAILED TO START" report:
 * the description says what went wrong and where, such as the property key or class, and the action
 * says how to fix it and where the error codes page explains it. Spring Boot loads it from {@code
 * META-INF/spring.factories}.
 */
public final class StratumStartupFailureAnalyzer implements FailureAnalyzer {

  /** The start of every Stratum code. */
  private static final String STRATUM_CODE_PREFIX = "SX-";

  /** The page that explains every Stratum code and its fix. */
  static final String ERROR_CODES_PAGE =
      "https://github.com/exepex/stratum/blob/main/docs/guides/error-codes.md";

  /**
   * Describes the first failure with a Stratum code among the failure and its causes.
   *
   * @param failure what stopped startup
   * @return the report, or {@code null} to leave a failure without a Stratum code to Spring Boot
   */
  @Override
  public @Nullable FailureAnalysis analyze(Throwable failure) {
    return Stream.iterate(failure, Objects::nonNull, Throwable::getCause)
        .filter(FailureWithErrorCode.class::isInstance)
        .map(FailureWithErrorCode.class::cast)
        .filter(this::hasStratumCode)
        .findFirst()
        .map(codedFailure -> analysisOf(codedFailure, failure))
        .orElse(null);
  }

  /** Returns whether the failure's code is one of Stratum's own. */
  private boolean hasStratumCode(FailureWithErrorCode codedFailure) {
    return codedFailure.getErrorCode().getCode().startsWith(STRATUM_CODE_PREFIX);
  }

  /** Makes the report: the code and message, then the fix and the page that explains the code. */
  private FailureAnalysis analysisOf(FailureWithErrorCode codedFailure, Throwable failure) {
    var errorCode = codedFailure.getErrorCode();
    var fix = errorCode.getFix();
    var pointer = "See " + errorCode.getCode() + " in " + ERROR_CODES_PAGE + ".";
    var action = fix.isEmpty() ? pointer : fix + " " + pointer;
    return new FailureAnalysis(codedFailure.getReport().getCodeAndMessage(), action, failure);
  }
}
