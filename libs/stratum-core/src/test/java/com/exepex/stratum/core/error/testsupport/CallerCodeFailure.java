package com.exepex.stratum.core.error.testsupport;

import com.exepex.stratum.core.error.CoreErrorCode;
import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.FailureWithErrorCode;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

/**
 * A failure with a Stratum code that has no fix, as a failed call or a timed-out statement has. The
 * code is a warning, which a {@code StratumException} refuses, so this failure carries it.
 */
@Getter
public class CallerCodeFailure extends RuntimeException implements FailureWithErrorCode {

  private static final long serialVersionUID = 1L;

  /** The code, a Stratum one with no fix. */
  private final ErrorCode errorCode = CoreErrorCode.SPRING_BOOT_VERSION_UNSUPPORTED;

  /** No detail. */
  private final String errorDetail = "";

  /**
   * Makes the failure.
   *
   * @param cause what it wraps, or {@code null}
   */
  public CallerCodeFailure(@Nullable Throwable cause) {
    super("a call failed", cause);
  }
}
