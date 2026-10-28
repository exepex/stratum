package com.exepex.stratum.core.error.testsupport;

import com.exepex.stratum.core.error.ErrorDetail;
import lombok.experimental.UtilityClass;

/** Values shared by the error tests: an item, a field problem and the longest ID a report keeps. */
@UtilityClass
public class ErrorTestData {

  /** The length of the longest ID that an error report keeps. */
  public static final int LONGEST_ID_LENGTH = 128;

  /** A problem with one field of an order. */
  public static final ErrorDetail QUANTITY_PROBLEM =
      new ErrorDetail("quantity", "must be positive");

  /** The item the test orders ask for. */
  public static final String SKU = "SKU-1";
}
