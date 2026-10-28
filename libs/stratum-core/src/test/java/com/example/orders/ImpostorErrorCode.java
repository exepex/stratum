package com.example.orders;

import com.exepex.stratum.core.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Error codes a service declares with Stratum's reserved prefix, which Stratum must reject. */
@Getter
@RequiredArgsConstructor
public enum ImpostorErrorCode implements ErrorCode {

  /** Reuses the code of one of Stratum's own errors with another meaning. */
  COPIED_STRATUM_CODE("SX-CORE-1002", "Something else went wrong.");

  /** The code. */
  private final String code;

  /** The description. */
  private final String description;
}
