package com.exepex.stratum.test.fixture;

/** A package-private base whose public method names the internal type. */
class HiddenGreeterBase {

  /**
   * Greets the world.
   *
   * @return the greeting, as the internal type a service must not use
   */
  public InternalGreeting greet() {
    return new InternalGreeting("Hello");
  }
}
