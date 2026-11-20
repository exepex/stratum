package com.exepex.stratum.webflux.it;

import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.Severity;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The service's own codes that its client's {@code errors} setting gives two error statuses, with
 * the fixed description Stratum gives every such code.
 */
@Getter
@RequiredArgsConstructor
enum TeamErrorCode implements ErrorCode {

  /** The other service answered 409; the service maps the code to 409 for its own caller. */
  CONFLICT("ORD-0409", Severity.ERROR, "The other service answered with an error"),

  /** The other service answered 422; the service gives the code no status of its own. */
  UNPROCESSABLE("ORD-0422", Severity.ERROR, "The other service answered with an error");

  /** The code. */
  private final String code;

  /** Whether the code is an error or a warning. */
  private final Severity severity;

  /** The fixed description. */
  private final String description;
}
