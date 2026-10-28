package com.exepex.stratum.core.error;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import lombok.Value;
import lombok.With;
import org.jspecify.annotations.Nullable;

/**
 * Everything known about one error or warning, for the application's own logs: the code, its
 * severity and message, the problems with single input fields, the trace, span and correlation IDs,
 * and the cause with its stack trace. It never leaves the application; {@link #toExternal()} gives
 * the part that may.
 *
 * <p>Make one with {@link #of}, which reads the IDs from the current context, so nobody passes them
 * by hand. It is a class with a private constructor rather than a record, because a record's
 * constructor is always public and would let any code make a report with a Stratum code.
 */
@Value
public class ErrorReport implements Serializable {

  /** Version of the serialized form, kept so that a {@link StratumException} can be serialized. */
  @Serial private static final long serialVersionUID = 1L;

  /** The code, such as {@code SX-CORE-1002}. */
  String code;

  /** Whether the operation failed or only needs attention, from the kind of code. */
  Severity severity;

  /** The code's description with its placeholders filled in. */
  String message;

  /** The problems with single fields of the input; empty when there are none. */
  @With List<ErrorDetail> details;

  /** The trace, span and correlation IDs when the report was made. */
  DiagnosticIds ids;

  /**
   * The exception behind the problem, with its stack trace; {@code null} if none. Log it with the
   * report, as in {@code log.error(report.getCodeAndMessage(), report.getCause())}, and never send
   * it outside the application.
   */
  @With @Nullable Throwable cause;

  /**
   * Makes a report, keeping an unchangeable copy of the details.
   *
   * @param code the code
   * @param severity the severity
   * @param message the message
   * @param details the field problems
   * @param ids the diagnostic IDs
   * @param cause the exception behind the problem, if any
   */
  private ErrorReport(
      String code,
      Severity severity,
      String message,
      List<ErrorDetail> details,
      DiagnosticIds ids,
      @Nullable Throwable cause) {
    this.code = code;
    this.severity = severity;
    this.message = message;
    this.details = List.copyOf(details);
    this.ids = ids;
    this.cause = cause;
  }

  /**
   * Makes a report from a code, with no field details and no cause. Add them with {@link
   * #withDetails} and {@link #withCause}.
   *
   * @param code the error or warning code
   * @param arguments the values for the placeholders in the code's description; they can be shown
   *     outside the application, so never pass a secret, personal data or an internal detail
   * @return the report, with the IDs of the current context
   * @throws IllegalArgumentException if the code uses Stratum's prefix {@code SX-} but is not
   *     declared by Stratum
   */
  public static ErrorReport of(MessageCode code, Object... arguments) {
    requireOwnPrefix(code);
    return new ErrorReport(
        code.getCode(),
        severityOf(code),
        code.describe(arguments),
        List.of(),
        DiagnosticIds.current(),
        null);
  }

  /**
   * Returns the code and message in one line, as logs and exception messages show them.
   *
   * @return the line, such as {@code "SX-CORE-1002: Greeter returned null. ..."}
   */
  public String getCodeAndMessage() {
    return code + ErrorConstants.CODE_SEPARATOR + message;
  }

  /**
   * Returns the part of this report that may be shown outside the application: everything but the
   * span ID, the cause and the stack trace.
   *
   * @return the external error
   */
  public ExternalError toExternal() {
    return new ExternalError(code, severity, message, details, ids.traceId(), ids.correlationId());
  }

  /**
   * Rejects a code that uses Stratum's prefix but is declared outside Stratum, so that a service
   * cannot declare one by mistake.
   *
   * @param code the code to check
   */
  private static void requireOwnPrefix(MessageCode code) {
    if (usesStratumPrefix(code.getCode())
        && !code.getClass().getName().startsWith(ErrorConstants.STRATUM_PACKAGE)) {
      throw new IllegalArgumentException(
          code.getClass().getName()
              + " declares "
              + code.getCode()
              + ", but the prefix "
              + ErrorConstants.STRATUM_CODE_PREFIX
              + " is reserved for Stratum. Give the service's codes their own prefix.");
    }
  }

  /**
   * Returns whether a code starts with Stratum's prefix, ignoring case and leading spaces, so that
   * {@code " sx-core-1002"} counts too. The check stops mistakes; a class in Stratum's package can
   * still declare any code.
   *
   * @param code the code
   * @return whether the code looks like one of Stratum's
   */
  private static boolean usesStratumPrefix(String code) {
    var prefix = ErrorConstants.STRATUM_CODE_PREFIX;
    return code.strip().regionMatches(true, 0, prefix, 0, prefix.length());
  }

  /**
   * Returns the severity that a code's kind gives every report.
   *
   * @param code the code
   * @return {@link Severity#ERROR} for an {@link ErrorCode}, {@link Severity#WARNING} for a {@link
   *     WarningCode}
   */
  private static Severity severityOf(MessageCode code) {
    return switch (code) {
      case ErrorCode _ -> Severity.ERROR;
      case WarningCode _ -> Severity.WARNING;
    };
  }
}
