package com.exepex.stratum.restclient.testsupport.payments;

import java.io.Serial;

/** The team's own exception for a payment the payments service does not know, from a 404. */
public class PaymentMissingException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public PaymentMissingException() {
    super("The payment does not exist");
  }
}
