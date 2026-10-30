package com.exepex.stratum.test.fixture.autoconfigure;

/** Breaks the rules: logic in the autoconfigure package, which may only create and wire beans. */
public class GreetingFormatter {

  /**
   * Formats a greeting.
   *
   * @param name who to greet
   * @return the greeting
   */
  public String greet(String name) {
    return "Hello, " + name;
  }
}
