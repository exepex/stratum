package com.exepex.stratum.restclient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One call a mocked client received, as {@link MockClient#calls} returns it. Its text shows only
 * how many arguments it had, never their values, which may hold personal data (ST-SEC-03).
 *
 * @param method the name of the interface method called
 * @param arguments the arguments it was called with, in order
 */
public record RecordedCall(String method, List<@Nullable Object> arguments) {

  /** Keeps a fixed copy of the arguments, which may hold {@code null}. */
  public RecordedCall {
    arguments = Collections.unmodifiableList(new ArrayList<>(arguments));
  }

  /**
   * Returns the call's method and the number of its arguments, without their values.
   *
   * @return the text
   */
  @Override
  public String toString() {
    return "RecordedCall[method=" + method + ", " + arguments.size() + " arguments hidden]";
  }
}
