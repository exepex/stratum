package com.exepex.stratum.core.error;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;
import lombok.With;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * Everything known about one error or warning, for the application's own logs: the {@linkplain
 * #getExternal() external error} that a caller may see, plus the span ID and the cause with its
 * stack trace, which stay inside the application.
 *
 * <p>Make one with {@link #of}, which reads the trace, span and correlation IDs from the logging
 * context, so nobody passes them by hand. It is a class with a private constructor rather than a
 * record, because a record's constructor is always public and would skip the checks that {@link
 * #of} makes.
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

  /**
   * The part a caller may see: code, severity, message, field details, trace ID and correlation ID.
   */
  ExternalError external;

  /** The span ID of the operation, set by tracing; {@code null} without tracing. */
  @Nullable String spanId;

  /**
   * The exception behind the problem, with its stack trace; {@code null} if none. It is for the
   * logs only and never leaves the application.
   */
  @With @Nullable Throwable cause;

  /**
   * Makes a report from a code alone, with the trace, span and correlation IDs of the current
   * thread's logging context (SLF4J's MDC). Its message is the code's description.
   *
   * @param code the error or warning code
   * @return the report
   * @throws IllegalArgumentException if the code uses Stratum's prefix {@code SX-} but is not
   *     declared by Stratum
   */
  public static ErrorReport of(ErrorCode code) {
    return create(code, code.getDescription());
  }

  /**
   * Makes a report from a code and one detail, such as the name of the class at fault. Its message
   * is the code's description, a colon and the detail.
   *
   * @param code the error or warning code
   * @param detail what is specific to this occurrence. It can be shown outside the application, so
   *     never include a secret, personal data or an internal detail. Line breaks and other control
   *     characters become spaces, so a value from a request cannot forge a log line.
   * @return the report
   * @throws IllegalArgumentException if the code uses Stratum's prefix {@code SX-} but is not
   *     declared by Stratum
   */
  public static ErrorReport of(ErrorCode code, String detail) {
    return create(
        code, code.getDescription() + ": " + CONTROL_CHARACTERS.matcher(detail).replaceAll(" "));
  }

  /**
   * Returns a copy of this report with problems of single input fields, such as the fields of a
   * request body that failed validation.
   *
   * @param fieldProblems the problems; the report keeps an unchangeable copy
   * @return the new report
   */
  public ErrorReport withDetails(List<ErrorDetail> fieldProblems) {
    var updated =
        new ExternalError(
            external.code(),
            external.severity(),
            external.message(),
            fieldProblems,
            external.traceId(),
            external.correlationId());
    return new ErrorReport(updated, spanId, cause);
  }

  /**
   * Returns the code and message in one line, as logs and exception messages show them.
   *
   * @return the line, such as {@code "SX-CORE-1002: A StratumPropertiesCustomizer is broken: ..."}
   */
  public String getCodeAndMessage() {
    return external.code() + ": " + external.message();
  }

  /**
   * Makes a report with the given message, after checking the code.
   *
   * @param code the error or warning code
   * @param message the message
   * @return the report
   */
  private static ErrorReport create(ErrorCode code, String message) {
    requireOwnPrefix(code);
    var external =
        new ExternalError(
            code.getCode(),
            code.getSeverity(),
            message,
            List.of(),
            usableIdOrNull("traceId"),
            usableIdOrNull("correlationId"));
    return new ErrorReport(external, usableIdOrNull("spanId"), null);
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
