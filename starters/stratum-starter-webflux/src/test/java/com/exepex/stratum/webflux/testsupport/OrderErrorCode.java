package com.exepex.stratum.webflux.testsupport;

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

  /** A code the service forgot to map. */
  UNMAPPED("ORD-0003", Severity.ERROR, "The order could not be priced");

  /** The code. */
  private final String code;

  /** Whether the order failed or only needs attention. */
  private final Severity severity;

  /** The fixed description. */
  private final String description;
}
