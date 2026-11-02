package com.exepex.stratum.logging.redaction;

import com.exepex.stratum.core.logging.LoggingContextConstants;
import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.LoggingProperties.LogFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;

/**
 * The fields of a JSON log line whose kind is known from the format Spring Boot writes: fields that
 * Stratum, Spring Boot or the web module fill and that never hold personal data, written as they
 * are, and the stack trace fields, whose frame lines are not searched. Paths are matched whole, so
 * a service's own field such as {@code service.contact} is still masked.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class KnownLogFields {

  /** The web module's request fields that hold no personal data. */
  private static final List<String> SAFE_WEB_FIELDS =
      List.of(
          LoggingContextConstants.TRACE_ID,
          LoggingContextConstants.SPAN_ID,
          LoggingContextConstants.HTTP_METHOD,
          LoggingContextConstants.HTTP_ROUTE,
          LoggingContextConstants.USER_AGENT,
          LoggingContextConstants.DEVICE_CLASS,
          LoggingContextConstants.DEVICE_NAME,
          LoggingContextConstants.OS_NAME,
          LoggingContextConstants.OS_VERSION,
          LoggingContextConstants.BROWSER_NAME,
          LoggingContextConstants.BROWSER_VERSION);

  /** GELF writes every additional field with a leading underscore. */
  private static final String GELF_FIELD_PREFIX = "_";

  /** Known nowhere: every field is searched and no stack trace line is skipped. */
  private static final KnownLogFields NONE = new KnownLogFields(Set.of(), Set.of());

  /** The full paths written as they are. */
  private final Set<String> safePaths;

  /** The full paths that hold a stack trace. */
  private final Set<String> stackTracePaths;

  /**
   * Returns the fields known for the formats of the console and the log file. When the two differ,
   * only the fields known in both count; when either is not one of Spring Boot's formats, none do.
   *
   * @param environment the application's environment
   * @return the known fields
   */
  static KnownLogFields from(Environment environment) {
    var formatNames =
        Stream.of(LoggingConstants.CONSOLE_FORMAT, LoggingConstants.FILE_FORMAT)
            .map(environment::getProperty)
            .filter(Objects::nonNull)
            .toList();
    var formats = formatNames.stream().map(KnownLogFields::formatNamed).toList();
    if (formats.isEmpty() || formats.stream().anyMatch(Optional::isEmpty)) {
      return NONE;
    }
    var knownFormats = formats.stream().map(Optional::orElseThrow).toList();
    return new KnownLogFields(
        knownInAll(knownFormats, KnownLogFields::safePathsOf),
        knownInAll(knownFormats, KnownLogFields::stackTracePathsOf));
  }

  /**
   * Returns whether the field is written as it is.
   *
   * @param path the field's full path, such as {@code log.level}
   * @return whether the field never holds personal data
   */
  boolean isSafe(String path) {
    return safePaths.contains(path);
  }

  /**
   * Returns whether the field holds a stack trace.
   *
   * @param path the field's full path, such as {@code error.stack_trace}
   * @return whether the field is a stack trace
   */
  boolean isStackTrace(String path) {
    return stackTracePaths.contains(path);
  }

  /** Returns Spring Boot's format with the name, ignoring case as Spring Boot does. */
  private static Optional<LogFormat> formatNamed(String name) {
    return Arrays.stream(LogFormat.values()).filter(format -> isNamed(format, name)).findFirst();
  }

  /** Returns whether the format has the name, ignoring case. */
  private static boolean isNamed(LogFormat format, String name) {
    var structuredFormat = format.getStructuredFormat();
    return structuredFormat != null
        && String.CASE_INSENSITIVE_ORDER.compare(structuredFormat, name) == 0;
  }

  /** Returns the paths that every one of the formats lists. */
  private static Set<String> knownInAll(
      List<LogFormat> formats, Function<LogFormat, Set<String>> pathsOf) {
    return formats.stream().map(pathsOf).reduce(KnownLogFields::bothHold).orElse(Set.of());
  }

  /** Returns the paths that both sets hold. */
  private static Set<String> bothHold(Set<String> first, Set<String> second) {
    return first.stream().filter(second::contains).collect(Collectors.toUnmodifiableSet());
  }

  /** Returns the safe paths of a format: Spring Boot's own fields, Stratum's error code. */
  private static Set<String> safePathsOf(LogFormat format) {
    return switch (format) {
      case ECS ->
          withWebFields(
              "",
              "@timestamp",
              "log.level",
              "log.logger",
              "process.pid",
              "process.thread.name",
              "service.name",
              "service.version",
              "service.environment",
              "service.node.name",
              "ecs.version",
              "error.type",
              LoggingConstants.ERROR_CODE);
      case LOGSTASH ->
          withWebFields(
              "",
              "@timestamp",
              "@version",
              "logger_name",
              "thread_name",
              "level",
              "level_value",
              LoggingConstants.ERROR_CODE);
      case GELF ->
          withWebFields(
              GELF_FIELD_PREFIX,
              "version",
              "host",
              "timestamp",
              "level",
              "_level_name",
              "_process_pid",
              "_process_thread_name",
              "_log_logger",
              "_service_version",
              "_error_type",
              LoggingConstants.GELF_ERROR_CODE);
      case TEXT -> Set.of();
    };
  }

  /** Returns the stack trace paths of a format; GELF's full message holds one too. */
  private static Set<String> stackTracePathsOf(LogFormat format) {
    return switch (format) {
      case ECS -> Set.of("error.stack_trace");
      case LOGSTASH -> Set.of("stack_trace");
      case GELF -> Set.of("_error_stack_trace", "full_message");
      case TEXT -> Set.of();
    };
  }

  /** Returns the paths with the web module's fields, each written after the prefix. */
  private static Set<String> withWebFields(String webFieldPrefix, String... paths) {
    return Stream.concat(
            Arrays.stream(paths), SAFE_WEB_FIELDS.stream().map(field -> webFieldPrefix + field))
        .collect(Collectors.toUnmodifiableSet());
  }
}
