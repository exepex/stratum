package com.exepex.stratum.web.testsupport;

import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.Severity;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** A service's own error codes, with the service's own prefix. */
@Getter
@RequiredArgsConstructor
public enum OrderErrorCode implements ErrorCode {

  /** The order exists already; the service maps it to 409. */
  DUPLICATE_ORDER("ORD-0001", Severity.ERROR, "Order already exists"),

  /** The order store did not answer; the service maps it to 503. */
  STORE_DOWN("ORD-0002", Severity.ERROR, "The order store is not available"),

  /** A code the service forgot to map. */
  UNMAPPED("ORD-0003", Severity.ERROR, "The order could not be priced");

  /** The code. */
  private final String code;

  /** Whether the order failed or only needs attention. */
  private final Severity severity;

  /** The fixed description. */
  private final String description;
}
