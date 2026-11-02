package com.exepex.stratum.test.fixture;

/** Breaks the rules: an API type whose protected nested type, open to subclasses, leaks. */
public class ExtensibleGreeter {

  /** An extension point that a service's subclass can use. */
  protected static class Extension {

    /**
     * Greets the world.
     *
     * @return the greeting, as the internal type a service must not use
     */
    public InternalGreeting greet() {
      return new InternalGreeting("Hello");
    }
  }
}
