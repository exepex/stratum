package com.exepex.stratum.core.error;

import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;
import lombok.With;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.slf4j.helpers.MessageFormatter;

/**
 * Everything known about one error or warning, for the application's own logs: the code, its
 * severity and message, the problems with single input fields, the trace, span and correlation IDs,
 * and the cause with its stack trace. It never leaves the application; {@link #toExternal()} gives
 * the part that may.
 *
 * <p>Make one with {@link #of}, which reads the IDs from the logging context, so nobody passes them
 * by hand. It is a class with a private constructor rather than a record, because a record's
 * constructor is always public and would skip the checks that {@link #of} makes.
 */
@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ErrorReport implements Serializable {

  /** Version of the serialized form, kept so that a {@link StratumException} can be serialized. */
  @Serial private static final long serialVersionUID = 1L;

  /** The start of every Stratum code. No service may declare a code that starts with it. */
  private static final String STRATUM_CODE_PREFIX = "SX-";

  /** The package that every class allowed to declare a Stratum code lives in. */
  private static final String STRATUM_PACKAGE = "com.exepex.stratum.";

  /**
   * What a usable trace, span or correlation ID looks like: 1 to 128 letters, digits, {@code -},
   * {@code _}, {@code .} or {@code :}. Anything else, such as a line break, could forge a log line
   * or break a response, so the report leaves it out.
   */
  private static final Pattern USABLE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

  /** Line breaks and every other control character, which could forge a log line. */
  private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{Cntrl}");

  /** The code, such as {@code SX-CORE-1002}. */
  String code;

  /** Whether the operation failed or only needs attention. */
  Severity severity;

  /** The code's description with its placeholders filled in. */
  String message;

  /** The problems with single fields of the input, unchangeable; empty when there are none. */
  List<ErrorDetail> details;

  /** The trace ID of the request, set by tracing; {@code null} without tracing. */
  @Nullable String traceId;

  /** The span ID of the operation, set by tracing; {@code null} without tracing. */
  @Nullable String spanId;

  /** The ID that ties the steps of one business workflow together; {@code null} if none was set. */
  @Nullable String correlationId;

  /**
   * The exception behind the problem, with its stack trace; {@code null} if none. Log it with the
   * report, as in {@code log.error(report.getCodeAndMessage(), report.getCause())}, and never send
   * it outside the application.
   */
  @With @Nullable Throwable cause;

  /**
   * Makes a report from a code, with no field details and no cause, and with the trace, span and
   * correlation IDs of the current thread's logging context (SLF4J's MDC). Add details and a cause
   * with {@link #withDetails} and {@link #withCause}.
   *
   * @param code the error or warning code
   * @param arguments the values for the {@code {}} placeholders in the code's description. The
   *     message can be shown outside the application, so never pass a secret, personal data or an
   *     internal detail, and pass an exception as the cause, never as an argument. Line breaks and
   *     other control characters in an argument become spaces, so a value from a request cannot
   *     forge a log line.
   * @return the report
   * @throws IllegalArgumentException if the code uses Stratum's prefix {@code SX-} but is not
   *     declared by Stratum
   */
  public static ErrorReport of(ErrorCode code, Object... arguments) {
    requireOwnPrefix(code);
    return new ErrorReport(
        code.getCode(),
        code.getSeverity(),
        describe(code, arguments),
        List.of(),
        usableIdOrNull("traceId"),
        usableIdOrNull("spanId"),
        usableIdOrNull("correlationId"),
        null);
  }

  /**
   * Returns a copy of this report with problems of single input fields, such as the fields of a
   * request body that failed validation.
   *
   * @param fieldProblems the problems; the report keeps an unchangeable copy
   * @return the new report
   */
  public ErrorReport withDetails(List<ErrorDetail> fieldProblems) {
    return new ErrorReport(
        code, severity, message, List.copyOf(fieldProblems), traceId, spanId, correlationId, cause);
  }

  /**
   * Returns the code and message in one line, as logs and exception messages show them.
   *
   * @return the line, such as {@code "SX-CORE-1002: Greeter returned null. ..."}
   */
  public String getCodeAndMessage() {
    return code + ": " + message;
  }

  /**
   * Returns the part of this report that may be shown outside the application: everything but the
   * span ID and the cause with its stack trace.
   *
   * @return the external error
   */
  public ExternalError toExternal() {
    return new ExternalError(code, severity, message, details, traceId, correlationId);
  }

  /**
   * Rejects a code that uses Stratum's prefix, in any case and after any leading spaces, but is
   * declared outside Stratum's package, so that a service cannot declare one by mistake.
   *
   * @param code the code to check
   */
  private static void requireOwnPrefix(ErrorCode code) {
    var usesStratumPrefix =
        code.getCode()
            .strip()
            .regionMatches(true, 0, STRATUM_CODE_PREFIX, 0, STRATUM_CODE_PREFIX.length());
    if (usesStratumPrefix && !code.getClass().getName().startsWith(STRATUM_PACKAGE)) {
      throw new IllegalArgumentException(
          code.getClass().getName()
              + " declares "
              + code.getCode()
              + ", but the prefix "
              + STRATUM_CODE_PREFIX
              + " is reserved for Stratum. Give the service's codes their own prefix.");
    }
  }

  /**
   * Fills the code's placeholders with the arguments as text, each with its control characters
   * replaced by a space. SLF4J's {@code {}} placeholders, unlike {@link String#format} patterns,
   * never read an argument as part of the pattern.
   *
   * @param code the code
   * @param arguments the values for the placeholders
   * @return the message
   */
  private static String describe(ErrorCode code, Object... arguments) {
    Object[] safeArguments =
        Arrays.stream(arguments)
            .map(argument -> CONTROL_CHARACTERS.matcher(String.valueOf(argument)).replaceAll(" "))
            .toArray();
    return MessageFormatter.basicArrayFormat(code.getDescription(), safeArguments);
  }

  /**
   * Reads an ID from the current thread's logging context and keeps it only if it is usable.
   *
   * @param key the logging context key, such as {@code traceId}
   * @return the ID, or {@code null} when it is missing or not usable
   */
  private static @Nullable String usableIdOrNull(String key) {
    var id = MDC.get(key);
    return id != null && USABLE_ID.matcher(id).matches() ? id : null;
  }
}
