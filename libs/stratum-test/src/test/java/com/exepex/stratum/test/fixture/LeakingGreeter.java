package com.exepex.stratum.test.fixture;

/** Breaks the rules: an API type whose public method returns an internal type. */
public class LeakingGreeter {

  /**
   * Greets a caller.
   *
   * @param name the caller's name
   * @return the greeting, as the internal type a service must not use
   */
  public InternalGreeting greet(String name) {
    return new InternalGreeting("Hello " + name);
  }
}
