package com.exepex.stratum.core.error;

/**
 * An exception that carries an {@link ErrorCode}, so Stratum answers and logs it as it does a
 * {@link StratumException}: the status mapped to its code, the same error body without field
 * details, and the code in the {@code error.code} log field.
 *
 * <p>It is a migration path for a service's existing exceptions: implement it on them now, and move
 * them to {@code StratumException} over time. New code throws {@code StratumException}.
 */
public interface FailureWithErrorCode {

  /**
   * The code of the failure, such as the service's own {@code PAY-0001}.
   *
   * @return the code
   */
  ErrorCode getErrorCode();

  /**
   * What is specific to this failure, added after the code's description; empty for none. It can be
   * shown outside the application, so it never holds a secret, personal data or an internal detail.
   *
   * @return the detail
   */
  String getErrorDetail();

  /**
   * The report Stratum answers and logs the failure with, made from the code and detail with the
   * IDs of the current logging context. {@code StratumException} returns the report it was made
   * with.
   *
   * @return the report
   * @throws IllegalArgumentException if the code uses Stratum's prefix {@code SX-} but is not
   *     declared by Stratum
   */
  default ErrorReport getReport() {
    return ErrorReport.of(getErrorCode(), getErrorDetail());
  }
}
