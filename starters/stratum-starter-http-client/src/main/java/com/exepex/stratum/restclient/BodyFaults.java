package com.exepex.stratum.restclient;

import java.io.EOFException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;

/**
 * Tells a body that is not valid from bytes lost on the way, among the causes of a failed call. A
 * parser's failure is known by its class name, so that Stratum needs none of these libraries.
 */
@UtilityClass
class BodyFaults {

  /**
   * The read failures of the parsers a client can be given, all of which extend {@link
   * java.io.IOException}: Jackson 2's base class, protobuf's and Gson's bad JSON.
   */
  private static final Set<String> PARSER_FAILURES =
      Set.of(
          "com.fasterxml.jackson.core.JacksonException",
          "com.google.protobuf.InvalidProtocolBufferException",
          "com.google.gson.stream.MalformedJsonException");

  /** Gson's wrapper of every read failure, the network's included. */
  private static final String GSON_FAILURE = "com.google.gson.JsonSyntaxException";

  /**
   * Returns whether a cause is the body's own fault, not lost bytes: too large, or not valid for
   * its parser. The causes below it may still hold a real I/O failure, such as a read timeout.
   *
   * @param cause a cause of the failed call
   * @return whether it is the body's fault
   */
  static boolean isBodyFault(Throwable cause) {
    return cause instanceof ResponseBodyTooLarge
        || Stream.<Class<?>>iterate(cause.getClass(), Objects::nonNull, Class::getSuperclass)
            .anyMatch(type -> PARSER_FAILURES.contains(type.getName()));
  }

  /**
   * Returns whether a cause is Gson's report of JSON that ends too soon: its wrapper around the end
   * of the input. Apache reports a body cut off by the network with its own exceptions, never a
   * bare {@link EOFException}, so the end of the input is the body's own end.
   *
   * @param cause a cause of the failed call
   * @return whether it is JSON that ended too soon, read by Gson
   */
  static boolean isGsonEndOfJson(Throwable cause) {
    return GSON_FAILURE.equals(cause.getClass().getName())
        && cause.getCause() instanceof EOFException;
  }
}
