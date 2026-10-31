package com.exepex.stratum.web;

import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The request fields that travel with a request's work to every thread it runs on.
 *
 * @param fields the fields known when the request arrived, by their names in the logging context
 * @param route the route the request matched, which is known only once Spring has matched it
 */
public record RequestFieldValues(Map<String, String> fields, Supplier<@Nullable String> route) {

  /** Keeps an unchangeable copy of the fields. */
  public RequestFieldValues {
    fields = Map.copyOf(fields);
  }
}
