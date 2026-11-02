package com.exepex.stratum.test.fixture;

/** Breaks the rules: an API type that names an internal type only in a {@code throws} clause. */
public class ThrowingGreeter {

  /**
   * Greets a caller, or fails.
   *
   * @param name the caller's name
   * @throws InternalGreetingFailure when the name is blank, as the internal type
   */
  public void greet(String name) throws InternalGreetingFailure {
    if (name.isBlank()) {
      throw new InternalGreetingFailure("no name");
    }
  }
}
