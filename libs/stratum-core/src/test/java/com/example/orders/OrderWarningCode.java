package com.example.orders;

import com.exepex.stratum.core.error.WarningCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** A service's own warning codes, with the service's own prefix. */
@Getter
@RequiredArgsConstructor
public enum OrderWarningCode implements WarningCode {

  /** The order was accepted, but it will ship late. */
  DELAYED("ORD-0101", "The order will ship {} late.");

  /** The code. */
  private final String code;

  /** The description, with a placeholder for the delay, such as {@code 3 days}. */
  private final String description;
}
