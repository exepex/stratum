package com.example.orders;

import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.Severity;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Error codes a service declares with Stratum's reserved prefix, which Stratum must reject. */
@Getter
@RequiredArgsConstructor
public enum ImpostorErrorCode implements ErrorCode {

  /** Reuses the code of one of Stratum's own errors with another meaning. */
  COPIED_STRATUM_CODE("SX-CORE-1002", Severity.ERROR, "Something else went wrong."),

  /** The same code written in lower case. */
  LOWER_CASE("sx-core-1002", Severity.ERROR, "Something else went wrong."),

  /** The same code behind a leading space. */
  LEADING_SPACE(" SX-CORE-1002", Severity.ERROR, "Something else went wrong.");

  /** The code. */
  private final String code;

  /** Whether the failure is an error or a warning. */
  private final Severity severity;

  /** The description. */
  private final String description;
}
