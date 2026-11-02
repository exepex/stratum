package com.exepex.stratum.test.fixture;

import java.util.function.Consumer;

/**
 * Breaks the rules: an API type that names an internal type only in type-parameter bounds.
 *
 * @param <G> the greetings it keeps, bounded by the internal type
 */
public class BoundLeakingGreeter<G extends InternalGreeting> {

  /**
   * Hands each greeting to a listener.
   *
   * @param <L> the listener, bounded by a type that names the internal type
   * @param listener the listener
   */
  public <L extends Consumer<InternalGreeting>> void notify(L listener) {
    listener.accept(new InternalGreeting("Hello"));
  }
}
