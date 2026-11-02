package com.exepex.stratum.logging;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.logging.LoggingProperties.LogFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;
import org.springframework.core.env.Environment;

/** Reads which of Spring Boot's structured formats the console and the log file write. */
@StratumInternal
@UtilityClass
public class StructuredLogFormats {

  /**
   * Returns the format of the console and then of the log file, for each that is set: Spring Boot's
   * format of that name, ignoring case as Spring Boot does, or empty for any other value, such as a
   * formatter's class name.
   *
   * @param environment the application's environment
   * @return the formats, console first; empty when neither is set
   */
  public static List<Optional<LogFormat>> of(Environment environment) {
    return Stream.of(LoggingConstants.CONSOLE_FORMAT, LoggingConstants.FILE_FORMAT)
        .map(environment::getProperty)
        .filter(Objects::nonNull)
        .map(StructuredLogFormats::formatNamed)
        .toList();
  }

  /** Returns Spring Boot's format with the name, ignoring case. */
  private static Optional<LogFormat> formatNamed(String name) {
    return Arrays.stream(LogFormat.values()).filter(format -> isNamed(format, name)).findFirst();
  }

  /** Returns whether the format has the name, ignoring case. */
  private static boolean isNamed(LogFormat format, String name) {
    var structuredFormat = format.getStructuredFormat();
    return structuredFormat != null
        && String.CASE_INSENSITIVE_ORDER.compare(structuredFormat, name) == 0;
  }
}
