package com.exepex.stratum.logging;

import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.Severity;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The codes of the logging module, each listed with its fix in {@code docs/guides/error-codes.md}.
 * Every error here stops the application at startup.
 */
@Getter
@RequiredArgsConstructor
public enum LoggingErrorCode implements ErrorCode {

  /** The service has no name, so its log lines could not be told apart from other services'. */
  APPLICATION_NAME_MISSING(
      "SX-LOGGING-1001",
      Severity.ERROR,
      "spring.application.name is not set; the logging module writes it on every line as"
          + " service.name"),

  /** A pattern in {@code stratum.logging.redaction.extra-patterns} cannot be compiled. */
  INVALID_REDACTION_PATTERN(
      "SX-LOGGING-1002",
      Severity.ERROR,
      "A pattern in stratum.logging.redaction.extra-patterns is not a valid regular expression");

  /** The code, such as {@code SX-LOGGING-1001}. */
  private final String code;

  /** Whether the operation failed or only needs attention. */
  private final Severity severity;

  /** What went wrong, in fixed text. */
  private final String description;
}
