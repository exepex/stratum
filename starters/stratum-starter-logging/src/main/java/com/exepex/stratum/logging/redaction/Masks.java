package com.exepex.stratum.logging.redaction;

import lombok.experimental.UtilityClass;

/** The text written in place of a masked value. */
@UtilityClass
class Masks {

  /** Replaces a masked value, or the part of it that is hidden. */
  static final String MASK = "****";

  /** How many characters an identifier such as a card number keeps visible at its end. */
  private static final int VISIBLE_END = 4;

  /**
   * Masks a value but keeps its last four characters, such as {@code ****1111} for a card; a value
   * of four characters or fewer is masked in full.
   *
   * @param value the value to mask
   * @return the masked value
   */
  String keepingLastFour(String value) {
    return value.length() <= VISIBLE_END
        ? MASK
        : MASK + value.substring(value.length() - VISIBLE_END);
  }
}
