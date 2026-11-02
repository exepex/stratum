package com.exepex.stratum.test.fixture;

import com.exepex.stratum.core.api.StratumInternal;

/** A failure only the made-up starter itself may catch. */
@StratumInternal
public class InternalGreetingFailure extends Exception {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the failure.
   *
   * @param message what went wrong
   */
  public InternalGreetingFailure(String message) {
    super(message);
  }
}
