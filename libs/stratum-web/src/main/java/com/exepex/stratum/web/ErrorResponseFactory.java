package com.exepex.stratum.web;

import com.exepex.stratum.core.error.ErrorReport;
import com.exepex.stratum.core.error.ExternalError;
import com.exepex.stratum.core.error.FailureWithErrorCode;
import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.core.error.Severity;
import com.exepex.stratum.core.error.StratumException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Makes the error body every caller gets: RFC 9457 Problem Details with the code, severity, field
 * details and request IDs, and never a stack trace, exception message or class name.
 */
@Slf4j
public class ErrorResponseFactory {

  /** What the log line of a code with no status adds. */
  private static final String UNMAPPED_CODE_NOTE =
      " (this code has no status in " + WebConstants.ERROR_STATUSES + ", so the caller got 500)";

  /** What the log line of a thrown warning code adds. */
  private static final String WARNING_THROWN_NOTE =
      " (this code is a warning, which is never thrown, so the caller got 500 and "
          + WebErrorCode.INTERNAL_ERROR.getCode()
          + ")";

  /** The member that holds the URI of the error's type; {@code about:blank} for Stratum's. */
  private static final String TYPE = "type";

  /** The type of an error that has no type of its own, as RFC 9457 defines it. */
  private static final URI NO_TYPE = URI.create("about:blank");

  /** The member that holds the reason phrase of the status, such as {@code Not Found}. */
  private static final String TITLE = "title";

  /** The member that holds the HTTP status. */
  private static final String STATUS = "status";

  /** The member that holds what went wrong. */
  private static final String DETAIL = "detail";

  /** The HTTP status of each of the service's error codes, from {@code stratum.web.error}. */
  private final Map<String, Integer> statusesByCode;

  /**
   * Makes the factory.
   *
   * @param settings the web settings, whose error statuses it uses
   */
  public ErrorResponseFactory(WebProperties settings) {
    this.statusesByCode = settings.error().statuses();
  }

  /**
   * Returns the body of a failure that carries an error code, a {@link StratumException} or an
   * exception that implements {@link FailureWithErrorCode}, with the status mapped to its code, and
   * logs it once: a 5xx at ERROR with its stack trace, a 4xx at WARN with its code and message. A
   * warning code, which is never thrown, gets 500 and {@code SX-WEB-1005}.
   *
   * @param failure what the request failed with
   * @return the body, which holds the status; empty if the failure carries no error code
   */
  public Optional<ProblemDetail> forFailureWithErrorCode(Throwable failure) {
    return failure instanceof FailureWithErrorCode codedFailure
        ? Optional.of(answerAndLog(failure, codedFailure.getReport()))
        : Optional.empty();
  }

  /**
   * Returns the body of a request that failed outside the service's own error handling, such as an
   * unknown path or a failed validation. It is not logged here: the server or Spring already did. A
   * failure with an error code that gets here, such as one thrown by a filter, keeps the status the
   * request failed with.
   *
   * @param status the status the request failed with
   * @param failure what it failed with, if known
   * @return the body, which holds the status
   */
  public ProblemDetail forFailedRequest(HttpStatusCode status, @Nullable Throwable failure) {
    if (failure instanceof FailureWithErrorCode codedFailure) {
      return bodyOf(externalErrorOf(codedFailure.getReport()), status);
    }
    if (failure instanceof RequestTimedOutException) {
      return bodyOf(ErrorReport.of(WebErrorCode.REQUEST_TIMED_OUT).getExternal(), status);
    }
    var fieldDetails = Optional.ofNullable(failure).flatMap(FieldErrorDetails::of);
    if (fieldDetails.isPresent()) {
      var report = ErrorReport.of(WebErrorCode.INVALID_FIELDS).withDetails(fieldDetails.get());
      return bodyOf(report.getExternal(), status);
    }
    return bodyOf(ErrorReport.of(codeFor(status)).getExternal(), status);
  }

  /**
   * Adds the request's trace and correlation IDs to an error body that does not have them yet, such
   * as one the service's own handler returned. An ID that is missing or unsafe for logs is left
   * out.
   *
   * @param body the error body
   * @param traceId the request's trace ID, if any
   * @param correlationId the request's correlation ID, if any
   */
  public void addRequestIds(
      ProblemDetail body, @Nullable String traceId, @Nullable String correlationId) {
    addIdIfMissing(body, LoggingContextConstants.TRACE_ID, traceId);
    addIdIfMissing(body, LoggingContextConstants.CORRELATION_ID, correlationId);
  }

  /**
   * Returns the members of an error body as a map, in the order they are written, for the error
   * handling of Spring Boot, which works with maps.
   *
   * @param body the error body
   * @return its members: {@code type}, {@code title}, {@code status}, {@code detail} and the added
   *     members
   */
  public Map<String, Object> membersOf(ProblemDetail body) {
    var members = new LinkedHashMap<String, Object>();
    members.put(TYPE, Objects.requireNonNullElse(body.getType(), NO_TYPE).toString());
    members.put(TITLE, Objects.requireNonNullElse(body.getTitle(), ""));
    members.put(STATUS, body.getStatus());
    members.put(DETAIL, Objects.requireNonNullElse(body.getDetail(), ""));
    Optional.ofNullable(body.getProperties()).ifPresent(members::putAll);
    return members;
  }

  /** Logs a failure with an error code once, and returns its body with the mapped status. */
  private ProblemDetail answerAndLog(Throwable failure, ErrorReport report) {
    if (isWarning(report)) {
      var logLine = logLineOf(report, WARNING_THROWN_NOTE);
      log.error(logLine, failure);
      return bodyOf(externalErrorOf(report), HttpStatus.INTERNAL_SERVER_ERROR);
    }
    var external = report.getExternal();
    var mappedStatus = Optional.ofNullable(statusesByCode.get(external.code()));
    var status =
        HttpStatusCode.valueOf(mappedStatus.orElse(HttpStatus.INTERNAL_SERVER_ERROR.value()));
    var logLine = logLineOf(report, mappedStatus.isEmpty() ? UNMAPPED_CODE_NOTE : "");
    if (status.is5xxServerError()) {
      log.error(logLine, failure);
    } else {
      log.warn(logLine);
    }
    return bodyOf(external, status);
  }

  /** Returns the log line of a report, with a note after its code and message. */
  private String logLineOf(ErrorReport report, String note) {
    // The message is already free of control characters; this also satisfies the log checks.
    return (report.getCodeAndMessage() + note).replaceAll("[\r\n]", " ");
  }

  /** Returns what the caller sees: the report's own error, or an internal error for a warning. */
  private ExternalError externalErrorOf(ErrorReport report) {
    return isWarning(report)
        ? ErrorReport.of(WebErrorCode.INTERNAL_ERROR).getExternal()
        : report.getExternal();
  }

  /** Returns whether the report's code is a warning, which a failure should never carry. */
  private boolean isWarning(ErrorReport report) {
    return report.getExternal().severity() == Severity.WARNING;
  }

  /** Returns the web code for a status that has no more specific cause. */
  private WebErrorCode codeFor(HttpStatusCode status) {
    return switch (HttpStatus.resolve(status.value())) {
      case BAD_REQUEST -> WebErrorCode.UNREADABLE_REQUEST;
      case NOT_FOUND -> WebErrorCode.NOT_FOUND;
      case CONTENT_TOO_LARGE -> WebErrorCode.REQUEST_BODY_TOO_LARGE;
      case INTERNAL_SERVER_ERROR -> WebErrorCode.INTERNAL_ERROR;
      case null, default -> WebErrorCode.REQUEST_FAILED;
    };
  }

  /** Returns the Problem Details body of an external error. */
  private ProblemDetail bodyOf(ExternalError external, HttpStatusCode status) {
    var body = ProblemDetail.forStatusAndDetail(status, external.message());
    body.setProperty(WebConstants.CODE, external.code());
    body.setProperty(WebConstants.SEVERITY, external.severity());
    if (!external.details().isEmpty()) {
      body.setProperty(WebConstants.DETAILS, external.details());
    }
    return body;
  }

  /** Adds one ID to the body, unless the body has it already or the ID is unsafe for logs. */
  private void addIdIfMissing(ProblemDetail body, String member, @Nullable String id) {
    var properties = body.getProperties();
    var alreadySet = properties != null && properties.containsKey(member);
    if (!alreadySet && LoggingContextConstants.isSafeForLogs(id)) {
      body.setProperty(member, id);
    }
  }
}
