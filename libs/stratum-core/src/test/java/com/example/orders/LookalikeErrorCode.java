package com.example.orders;

import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.Severity;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Error codes a service declares that only look like Stratum's reserved prefix, which Stratum must
 * accept and keep as written.
 */
@Getter
@RequiredArgsConstructor
public enum LookalikeErrorCode implements ErrorCode {

  /** Stratum's prefix written in lower case. */
  LOWER_CASE("sx-core-1002", Severity.ERROR, "Something else went wrong."),

  /** Stratum's prefix behind a leading space. */
  LEADING_SPACE(" SX-CORE-1002", Severity.ERROR, "Something else went wrong.");

  /** The code. */
  private final String code;

  /** Whether the failure is an error or a warning. */
  private final Severity severity;

  /** The description. */
  private final String description;
}
