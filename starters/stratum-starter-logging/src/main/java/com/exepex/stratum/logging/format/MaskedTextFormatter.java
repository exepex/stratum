package com.exepex.stratum.logging.format;

import com.exepex.stratum.logging.redaction.Redactor;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.logging.StackTracePrinter;
import org.springframework.boot.logging.StandardStackTracePrinter;
import org.springframework.boot.logging.structured.StructuredLogFormatter;

/**
 * Writes one log event as a masked line of text for people to read, the {@code text} format: time,
 * level, thread, logger, message, then each MDC entry and key-value pair as {@code key=value}, and
 * the stack trace, indented, on the lines below. Every part is masked as Stratum's JSON formats
 * mask it.
 *
 * @param <E> the logging backend's event type
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
abstract class MaskedTextFormatter<E> implements StructuredLogFormatter<E> {

  /** Ends every line, as Spring Boot's JSON formats do. */
  private static final String LINE_END = "\n";

  /** Starts each stack trace line that would otherwise start at the left edge. */
  private static final String STACK_TRACE_INDENT = "\t";

  /** The width the level is padded to, so that the messages line up. */
  private static final int LEVEL_WIDTH = 5;

  // The field names of Spring Boot's ECS format, so that a name-based rule masks text as JSON.

  /** The field name of the message. */
  private static final String MESSAGE_FIELD = "message";

  /** The field name of the thread. */
  private static final String THREAD_FIELD = "process.thread.name";

  /** The field name of the logger. */
  private static final String LOGGER_FIELD = "log.logger";

  /** The field name of the stack trace. */
  private static final String STACK_TRACE_FIELD = "error.stack_trace";

  /** Prints stack traces in Java's own layout, when no Spring Boot printer is set. */
  private static final StackTracePrinter JAVA_LAYOUT = StandardStackTracePrinter.rootLast();

  /** Masks values by field name and by content, made from the module's settings. */
  private final Redactor redactor;

  /**
   * Spring Boot's stack trace printer, set from {@code logging.structured.json.stacktrace.*};
   * {@code null} when none of those keys is set.
   */
  private final @Nullable StackTracePrinter stackTracePrinter;

  /**
   * Returns the event as one masked line, followed by its masked stack trace, if any.
   *
   * @param event the log event
   * @return the text to write
   */
  @Override
  public String format(E event) {
    var line = new StringBuilder();
    line.append(time(event))
        .append(' ')
        .append(padded(level(event)))
        .append(" [")
        .append(maskOnOneLine(THREAD_FIELD, thread(event)))
        .append("] ")
        .append(maskOnOneLine(LOGGER_FIELD, logger(event)))
        .append(" : ")
        .append(maskOnOneLine(MESSAGE_FIELD, message(event)));
    fields(event).forEach((key, value) -> appendField(line, key, value));
    var stackTrace = stackTrace(event);
    if (stackTrace != null) {
      line.append(LINE_END).append(indented(mask(STACK_TRACE_FIELD, stackTrace)));
    }
    return line.append(LINE_END).toString();
  }

  /**
   * Returns when the event happened.
   *
   * @param event the log event
   * @return the time
   */
  abstract Instant time(E event);

  /**
   * Returns the event's level, such as {@code INFO}.
   *
   * @param event the log event
   * @return the level
   */
  abstract String level(E event);

  /**
   * Returns the name of the thread that logged the event.
   *
   * @param event the log event
   * @return the thread name
   */
  abstract @Nullable String thread(E event);

  /**
   * Returns the name of the logger.
   *
   * @param event the log event
   * @return the logger name
   */
  abstract @Nullable String logger(E event);

  /**
   * Returns the message with its arguments filled in.
   *
   * @param event the log event
   * @return the message
   */
  abstract @Nullable String message(E event);

  /**
   * Returns the event's MDC entries and key-value pairs, in the order they are written.
   *
   * @param event the log event
   * @return the fields by name
   */
  abstract Map<String, @Nullable Object> fields(E event);

  /**
   * Returns the logged exception's stack trace, or {@code null} when the event has none.
   *
   * @param event the log event
   * @return the stack trace
   */
  abstract @Nullable String stackTrace(E event);

  /**
   * Returns the stack trace of an exception, as Spring Boot's printer writes it, else in Java's own
   * layout.
   *
   * @param exception the logged exception
   * @return the stack trace
   */
  String printStackTrace(Throwable exception) {
    var printer = stackTracePrinter != null ? stackTracePrinter : JAVA_LAYOUT;
    return printer.printStackTraceToString(exception);
  }

  /** Returns the level padded on the left to {@link #LEVEL_WIDTH} characters. */
  private String padded(String level) {
    return " ".repeat(Math.max(0, LEVEL_WIDTH - level.length())) + level;
  }

  /** Adds one field as {@code key=value}: the value masked under its key, both on one line. */
  private void appendField(StringBuilder line, String key, @Nullable Object value) {
    line.append(' ').append(onOneLine(key)).append('=').append(maskOnOneLine(key, value));
  }

  /** Masks a value and keeps it on one line. */
  private String maskOnOneLine(String fieldName, @Nullable Object value) {
    return onOneLine(mask(fieldName, value));
  }

  /**
   * Shows the line breaks in text as {@code \r} and {@code \n}, so that the text cannot start a
   * line of its own.
   */
  private String onOneLine(String text) {
    return text.replace("\r", "\\r").replace("\n", "\\n");
  }

  /**
   * Masks a value under its field name: the one place this format calls the redactor. The value is
   * masked as text, so the entries of a map or a list are masked too. The redactor turns the value
   * into text itself, so a {@code toString()} that fails hides the value instead of losing the
   * event.
   */
  private String mask(String fieldName, @Nullable Object value) {
    return redactor.redactAsText(fieldName, value);
  }

  /**
   * Indents every line of the stack trace that does not start with whitespace, so that only the
   * event's own line starts at the left edge, and a cause's message cannot forge a line.
   */
  private String indented(String stackTrace) {
    return stackTrace.lines().map(this::indentUnlessIndented).collect(Collectors.joining(LINE_END));
  }

  /** Returns the line with a tab in front unless it already starts with whitespace. */
  private String indentUnlessIndented(String line) {
    return !line.isEmpty() && Character.isWhitespace(line.charAt(0))
        ? line
        : STACK_TRACE_INDENT + line;
  }
}
