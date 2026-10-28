package com.example.orders;

import com.exepex.stratum.core.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** A service's own error codes, with the service's own prefix. */
@Getter
@RequiredArgsConstructor
public enum OrderErrorCode implements ErrorCode {

  /** The order asks for more items than are in stock. */
  OUT_OF_STOCK("ORD-0001", "Not enough stock for {}.");

  /** The code. */
  private final String code;

  /** The description, with a placeholder for the item. */
  private final String description;
}
