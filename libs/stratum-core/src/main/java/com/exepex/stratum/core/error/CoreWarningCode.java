package com.exepex.stratum.core.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The warnings of {@code stratum-core}, each listed in {@code docs/guides/error-codes.md}. The
 * module has none yet. A new warning takes the next {@code SX-CORE-} number not used by an error or
 * a warning, because errors and warnings of a module share one range of numbers.
 */
@Getter
@RequiredArgsConstructor
public enum CoreWarningCode implements WarningCode {
  ;

  /** The code, such as {@code SX-CORE-1006}. */
  private final String code;

  /** What needs attention and what to do, with placeholders for the details. */
  private final String description;
}
