package com.exepex.stratum.core.error;

import java.io.Serializable;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * An error or warning as it is shown outside the application: in an HTTP response body, on a
 * command line, in a batch job's result or in a user interface. Every Stratum module uses this one
 * shape, so a caller reads every service's errors the same way. It never holds a stack trace or the
 * cause; those stay in the {@link ErrorReport} it was made from, which goes to the logs. It is
 * serializable because a {@link StratumException} carries it inside its report.
 *
 * @param code the code, such as {@code SX-CORE-1002} or a service's own {@code PAY-0001}
 * @param severity whether the operation failed or only needs attention
 * @param message what went wrong, from the code's description
 * @param details the problems with single fields of the input; empty when there are none
 * @param traceId the trace ID of the request, so support can find the logs; {@code null} without
 *     tracing
 * @param correlationId the ID that ties the steps of one business workflow together; {@code null}
 *     when none was set
 */
public record ExternalError(
    String code,
    Severity severity,
    String message,
    List<ErrorDetail> details,
    @Nullable String traceId,
    @Nullable String correlationId)
    implements Serializable {

  /**
   * Keeps an unchangeable copy of the details.
   *
   * @param code the code
   * @param severity the severity
   * @param message the message
   * @param details the field problems
   * @param traceId the trace ID, if any
   * @param correlationId the correlation ID, if any
   */
  public ExternalError {
    details = List.copyOf(details);
  }
}
