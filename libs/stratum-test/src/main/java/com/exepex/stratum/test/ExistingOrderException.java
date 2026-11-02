package com.exepex.stratum.test;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.FailureWithErrorCode;
import java.io.Serial;
import lombok.Getter;

/**
 * An exception a sample orders service had before it used Stratum, which now implements {@link
 * FailureWithErrorCode} instead of extending {@code StratumException}, for the tests that check
 * both are answered and logged the same way.
 */
@StratumInternal
@Getter
public class ExistingOrderException extends RuntimeException implements FailureWithErrorCode {

  /** Version of the serialized form. */
  @Serial private static final long serialVersionUID = 1L;

  /** The code of the failure. */
  private final OrderErrorCode errorCode;

  /** What is specific to this failure. */
  private final String errorDetail;

  /**
   * Makes the exception, with a message of the service's own that Stratum never shows a caller.
   *
   * @param errorCode the code of the failure
   * @param errorDetail what is specific to this failure
   */
  public ExistingOrderException(OrderErrorCode errorCode, String errorDetail) {
    super("legacy order failure");
    this.errorCode = errorCode;
    this.errorDetail = errorDetail;
  }
}
