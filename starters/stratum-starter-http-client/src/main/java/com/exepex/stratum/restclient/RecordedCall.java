package com.exepex.stratum.restclient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One call a mocked client received, as {@link MockClient#calls} returns it.
 *
 * @param method the name of the interface method called
 * @param arguments the arguments it was called with, in order
 */
public record RecordedCall(String method, List<@Nullable Object> arguments) {

  /** Keeps a fixed copy of the arguments, which may hold {@code null}. */
  public RecordedCall {
    arguments = Collections.unmodifiableList(new ArrayList<>(arguments));
  }
}
