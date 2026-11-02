package com.exepex.stratum.logging.testsupport;

import lombok.experimental.UtilityClass;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import org.springframework.boot.logging.structured.StructuredLogFormatterFactory;
import org.springframework.core.env.Environment;

/** Creates Stratum's masked text formats the way Spring Boot's logging does, for both backends. */
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
}
