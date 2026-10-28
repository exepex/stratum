package com.exepex.stratum.core.error;

import java.util.Arrays;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/** Makes the arguments of a code's description safe to put in logs and responses (ST-SEC-04). */
@UtilityClass
class MessageArguments {

  /** Line breaks and every other control character, any of which could forge a log line. */
  private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{Cntrl}");

  /**
   * Returns each argument as text with its control characters, such as carriage return and line
   * feed, replaced by a space. A {@code null} argument becomes {@code "null"}, as in a log message.
   *
   * @param arguments the values for a description's placeholders
   * @return the safe values, in the same order
   */
  static Object[] withoutControlCharacters(@Nullable Object... arguments) {
    return Arrays.stream(arguments).map(MessageArguments::withoutControlCharacters).toArray();
  }

  /**
   * Returns one argument as text with its control characters replaced by a space.
   *
   * @param argument the value for one placeholder
   * @return the safe text
   */
  private static String withoutControlCharacters(@Nullable Object argument) {
    return CONTROL_CHARACTERS.matcher(String.valueOf(argument)).replaceAll(" ");
  }
}
