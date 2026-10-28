package com.exepex.stratum.core.error;

import java.io.Serial;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

/**
 * A failure with an {@link ErrorCode}. Its message starts with the code, such as {@code
 * SX-CORE-1002: ...}, and its {@linkplain #getReport() report} holds the code, message, details and
 * IDs. Log the exception itself, so the log shows its stack trace and cause.
 *
 * <p>Stratum throws it for its own errors; a service can throw it with its own codes.
 */
@Getter
public final class StratumException extends RuntimeException {

  /** Version of the serialized form. */
  @Serial private static final long serialVersionUID = 1L;

  /**
   * The code, message, details and IDs of the failure; {@link ErrorReport#toExternal()} gives the
   * part a caller may see.
   */
  private final ErrorReport report;

  /**
   * Makes the exception for an error code.
   *
   * @param code the error code
   * @param arguments the values for the placeholders in the code's description
   */
  public StratumException(ErrorCode code, Object... arguments) {
    this(ErrorReport.of(code, arguments));
  }

  /**
   * Makes the exception for an error code, caused by another exception.
   *
   * @param cause the exception behind the failure
   * @param code the error code
   * @param arguments the values for the placeholders in the code's description
   */
  public StratumException(Throwable cause, ErrorCode code, Object... arguments) {
    this(ErrorReport.of(code, arguments).withCause(cause));
  }

  /**
   * Makes the exception from a report, for example one with field details. The report's cause, if
   * any, becomes this exception's cause.
   *
   * @param report the report of an error
   * @throws IllegalArgumentException if the report is a warning, which is never thrown
   */
  public StratumException(ErrorReport report) {
    super(report.getCodeAndMessage(), causeOfError(report));
    this.report = report;
  }

  /**
   * Returns the cause of a report, after checking that it reports an error.
   *
   * @param report the report
   * @return the report's cause, if any
   */
  private static @Nullable Throwable causeOfError(ErrorReport report) {
    if (report.getSeverity() != Severity.ERROR) {
      throw new IllegalArgumentException(
          report.getCode() + " is a warning; log it instead of throwing it.");
    }
    return report.getCause();
  }
}
