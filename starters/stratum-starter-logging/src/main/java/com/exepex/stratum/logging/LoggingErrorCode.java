package com.exepex.stratum.logging;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.Severity;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The codes of the logging module, each listed with its fix in {@code docs/guides/error-codes.md}.
 * Every error here stops the application at startup.
 */
@StratumInternal
@Getter
@RequiredArgsConstructor
public enum LoggingErrorCode implements ErrorCode {

  /** The service has no name, so its log lines could not be told apart from other services'. */
  APPLICATION_NAME_MISSING(
      "SX-LOGGING-1001",
      Severity.ERROR,
      LoggingConstants.APPLICATION_NAME
          + " is not set; the logging module writes it on every line as service.name",
      "Set "
          + LoggingConstants.APPLICATION_NAME
          + " in application.properties or application.yml, or turn the module off with "
          + LoggingConstants.ENABLED
          + "=false."),

  /** A pattern in {@code stratum.logging.redaction.extra-patterns} cannot be compiled. */
  INVALID_REDACTION_PATTERN(
      "SX-LOGGING-1002",
      Severity.ERROR,
      "A pattern in " + LoggingConstants.EXTRA_PATTERNS + " is not a valid regular expression",
      "Correct the pattern. In .properties files, write each backslash twice."),

  /**
   * A log output does not use one of Stratum's formats, so Stratum could not mask what it writes.
   */
  LOG_FORMAT_NOT_STRATUM(
      "SX-LOGGING-1003",
      Severity.ERROR,
      "A log output does not use one of Stratum's formats, so its lines would not be masked",
      "Remove your own log format, Logback configuration file or logging.config, or add Logback or"
          + " Log4j2, and choose the format with stratum.logging.format. To keep your own"
          + " format, turn the module off with "
          + LoggingConstants.ENABLED
          + "=false.");

  /** The code, such as {@code SX-LOGGING-1001}. */
  private final String code;

  /** Whether the operation failed or only needs attention. */
  private final Severity severity;

  /** What went wrong, in fixed text. */
  private final String description;

  /** How to fix it, as the Fix column of the error codes page starts; empty for none. */
  private final String fix;
}
