package com.exepex.orders;

import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.Severity;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** A service's own error and warning codes, with the service's own prefix. */
@Getter
@RequiredArgsConstructor
public enum OrderErrorCode implements ErrorCode {

  /** The order asks for more items than are in stock. */
  OUT_OF_STOCK("ORD-0001", Severity.ERROR, "Not enough stock"),

  /** The order was accepted, but it will ship late. */
  DELAYED("ORD-0002", Severity.WARNING, "The order will ship late");

  /** The code. */
  private final String code;

  /** Whether the order failed or only needs attention. */
  private final Severity severity;

  /** The fixed description. */
  private final String description;
}
