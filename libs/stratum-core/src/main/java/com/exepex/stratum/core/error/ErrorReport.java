package com.exepex.stratum.core.error;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
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
 * context, so nobody passes them by hand.
 */
@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true, access = AccessLevel.PRIVATE)
public class ErrorReport implements Serializable {

  /** Version of the serialized form, kept so that a {@link StratumException} can be serialized. */
  @Serial private static final long serialVersionUID = 1L;

  /** The start of every Stratum code. No service may declare a code that starts with it. */
  private static final String STRATUM_CODE_PREFIX = "SX-";

  /** The package that every class allowed to declare a Stratum code lives in. */
  private static final String STRATUM_PACKAGE = "com.exepex.stratum.";

  /** Control characters and Unicode line and paragraph separators, which could forge a log line. */
  private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}]");

  /** The code of the error or warning, as declared. */
  ErrorCode errorCode;

  /** What is specific to this occurrence, as the message shows it; empty for none. */
  String detail;

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
    return of(code, "");
  }

  /**
   * Makes a report from a code and one detail, such as the name of the class at fault. Its message
   * is the code's description, a colon and the detail. A detail that is blank, or only control
   * characters, is left out, so the message is the description alone with no dangling colon.
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
    var safeDetail = CONTROL_CHARACTERS.matcher(detail).replaceAll(" ");
    var shownDetail = safeDetail.isBlank() ? "" : safeDetail;
    var description = code.getDescription();
    return create(
        code, shownDetail, shownDetail.isEmpty() ? description : description + ": " + shownDetail);
  }

  /**
   * Returns a copy of this report with problems of single input fields, such as the fields of a
   * request body that failed validation.
   *
   * @param fieldProblems the problems; the report keeps an unchangeable copy
   * @return the new report
   */
  public ErrorReport withDetails(List<ErrorDetail> fieldProblems) {
    return toBuilder().external(external.toBuilder().details(fieldProblems).build()).build();
  }

  /**
   * Returns the code and message in one line, as logs and exception messages show them.
   *
   * @return the line, such as {@code "SX-CORE-1002: A StratumPropertiesCustomizer is broken: ..."}
   */
  public String getCodeAndMessage() {
    return external.code() + ": " + external.message();
  }

  /** Makes a report with the given message, after checking the code. */
  private static ErrorReport create(ErrorCode code, String detail, String message) {
    requireOwnPrefix(code);
    var external =
        ExternalError.builder()
            .code(code.getCode())
            .severity(code.getSeverity())
            .message(message)
            .details(List.of())
            .traceId(safeIdOrNull(LoggingContextConstants.TRACE_ID))
            .correlationId(safeIdOrNull(LoggingContextConstants.CORRELATION_ID))
            .build();
    return ErrorReport.builder()
        .errorCode(code)
        .detail(detail)
        .external(external)
        .spanId(safeIdOrNull(LoggingContextConstants.SPAN_ID))
        .build();
  }

  /**
   * Rejects a code that starts with Stratum's prefix but is declared outside Stratum's package, so
   * that a service cannot declare one by mistake.
   */
  private static void requireOwnPrefix(ErrorCode code) {
    if (code.getCode().startsWith(STRATUM_CODE_PREFIX)
        && !code.getClass().getName().startsWith(STRATUM_PACKAGE)) {
      throw new IllegalArgumentException(
          code.getClass().getName()
              + " declares "
              + code.getCode()
              + ", but the prefix "
              + STRATUM_CODE_PREFIX
              + " is reserved for Stratum. Give the service's codes their own prefix.");
    }
  }

  /** Reads an ID from the current thread's logging context, or null if it is not safe for logs. */
  private static @Nullable String safeIdOrNull(String key) {
    var id = MDC.get(key);
    return LoggingContextConstants.isSafeForLogs(id) ? id : null;
  }
}
