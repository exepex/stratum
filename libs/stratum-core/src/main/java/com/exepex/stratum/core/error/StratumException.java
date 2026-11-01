package com.exepex.stratum.core.error;

import java.io.Serial;
import lombok.Getter;

/**
 * A failure with an {@link ErrorCode}. Its message starts with the code, such as {@code
 * SX-CORE-1002: ...}, and its {@linkplain #getReport() report} holds the code, message, details and
 * IDs. Log the exception itself, so the log shows its stack trace and cause.
 *
 * <p>Stratum throws it for its own errors; a service can throw it with its own codes. A service's
 * existing exceptions can implement {@link FailureWithErrorCode} instead, while they move to it.
 */
@Getter
public final class StratumException extends RuntimeException implements FailureWithErrorCode {

  /** Version of the serialized form. */
  @Serial private static final long serialVersionUID = 1L;

  /**
   * The code, message, details and IDs of the failure; {@code getReport().getExternal()} is the
   * part a caller may see.
   */
  private final ErrorReport report;

  /**
   * Makes the exception for an error code, with the code's description as its message.
   *
   * @param code the error code
   */
  public StratumException(ErrorCode code) {
    this(ErrorReport.of(code));
  }

  /**
   * Makes the exception for an error code and one detail, such as the name of the class at fault.
   *
   * @param code the error code
   * @param detail what is specific to this failure; see {@link ErrorReport#of(ErrorCode, String)}
   */
  public StratumException(ErrorCode code, String detail) {
    this(ErrorReport.of(code, detail));
  }

  /**
   * Makes the exception for an error code and one detail, caused by another exception.
   *
   * @param code the error code
   * @param detail what is specific to this failure; see {@link ErrorReport#of(ErrorCode, String)}
   * @param cause the exception behind the failure
   */
  public StratumException(ErrorCode code, String detail, Throwable cause) {
    this(ErrorReport.of(code, detail).withCause(cause));
  }

  /**
   * Makes the exception from a report, for example one with field details. The report's cause, if
   * any, becomes this exception's cause.
   *
   * @param report the report of an error
   * @throws IllegalArgumentException if the report is a warning, which is never thrown
   */
  public StratumException(ErrorReport report) {
    var external = report.getExternal();
    if (external.severity() != Severity.ERROR) {
      throw new IllegalArgumentException(
          external.code() + " is a warning; log it instead of throwing it.");
    }
    super(report.getCodeAndMessage(), report.getCause());
    this.report = report;
  }

  /**
   * Returns the code of the failure, from its report.
   *
   * @return the code
   */
  @Override
  public ErrorCode getErrorCode() {
    return report.getErrorCode();
  }

  /**
   * Returns what is specific to this failure, from its report; empty for none.
   *
   * @return the detail
   */
  @Override
  public String getErrorDetail() {
    return report.getDetail();
  }
}
