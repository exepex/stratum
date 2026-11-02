package com.exepex.stratum.logging.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.experimental.UtilityClass;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import org.springframework.boot.logging.structured.StructuredLogFormatterFactory;
import org.springframework.core.env.Environment;

/**
 * Creates Stratum's masked text formats the way Spring Boot's logging does, for both backends, and
 * checks what they write.
 */
@UtilityClass
public class MaskedTextFormats {

  /**
   * Returns the format Spring Boot creates from its class name, as it does for {@code
   * logging.structured.format.console}, with the stack trace printer the environment sets up.
   *
   * @param eventType the logging backend's event type, which Spring Boot checks the format against
   * @param formatterClassName the format's class name
   * @param environment the application's environment
   * @param <E> the event type
   * @return the format
   */
  public static <E> StructuredLogFormatter<E> createdBySpringBoot(
      Class<E> eventType, String formatterClassName, Environment environment) {
    return new StructuredLogFormatterFactory<E>(eventType, environment, _ -> {}, _ -> {})
        .get(formatterClassName);
  }

  /**
   * Checks that a masked text line has a stack trace below it and that every line of it starts with
   * whitespace, so that none can pass for a log line of its own.
   *
   * @param formattedEvent the formatted event, its own line first
   */
  public static void assertStackTraceIndented(String formattedEvent) {
    assertThat(formattedEvent.lines().skip(1).toList())
        .isNotEmpty()
        .allMatch(line -> !line.isEmpty() && Character.isWhitespace(line.charAt(0)));
  }
}
