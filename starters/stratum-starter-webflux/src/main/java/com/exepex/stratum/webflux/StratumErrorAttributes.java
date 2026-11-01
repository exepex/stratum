package com.exepex.stratum.webflux;

import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

import com.exepex.stratum.core.error.FailureWithErrorCode;
import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.web.ErrorResponseFactory;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.error.ErrorAttributeOptions.Include;
import org.springframework.boot.webflux.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.reactive.function.server.ServerRequest;

/**
 * Gives every request that failed outside the service's own exception handlers Stratum's error body
 * instead of Spring Boot's: a {@code StratumException}, or another exception that implements {@code
 * FailureWithErrorCode}, with the status mapped to its code, an unknown path, a failed validation,
 * an exception with {@code @ResponseStatus} or an unexpected exception. Spring Boot's error handler
 * writes it as JSON for an API caller and passes it to the error page of a browser.
 */
@RequiredArgsConstructor
public class StratumErrorAttributes extends DefaultErrorAttributes {

  /** The member of Spring Boot's error attributes that holds the status. */
  private static final String STATUS = "status";

  /**
   * The request attribute that keeps the body once made, so that a second read, as when a browser's
   * page falls back to the JSON body, does not log the failure again.
   */
  private static final String BODY = StratumErrorAttributes.class.getName() + ".body";

  /**
   * The request attribute that marks a failure with an error code as logged by Stratum, so Spring
   * Boot's error handler does not log it again.
   */
  static final String LOGGED_ATTRIBUTE = StratumErrorAttributes.class.getName() + ".logged";

  /** Makes the error body. */
  private final ErrorResponseFactory errorResponseFactory;

  /**
   * Returns Stratum's error body for the failed request, with its IDs, made once per request. The
   * options of {@code spring.web.error.include-*} do not apply: the body never holds a message,
   * stack trace or path.
   *
   * @param request the failed request
   * @param options ignored
   * @return the members of the error body
   */
  @Override
  public Map<String, Object> getErrorAttributes(
      ServerRequest request, ErrorAttributeOptions options) {
    var body =
        request.attribute(BODY).map(ProblemDetail.class::cast).orElseGet(() -> bodyOf(request));
    request.attributes().put(BODY, body);
    return errorResponseFactory.membersOf(body);
  }

  /**
   * Makes the error body of the failed request, and logs a failure with an error code. A code in a
   * cause is used only when Spring Boot would answer 500, so that a wrapper with a status of its
   * own, such as a {@code ResponseStatusException} or unreadable JSON, keeps Spring's answer.
   */
  private ProblemDetail bodyOf(ServerRequest request) {
    var failure = getError(request);
    var status = statusOf(request);
    var codedBody =
        failure instanceof FailureWithErrorCode || status.isSameCodeAs(INTERNAL_SERVER_ERROR)
            ? errorResponseFactory.forFailureWithErrorCode(failure)
            : Optional.<ProblemDetail>empty();
    codedBody.ifPresent(answered -> request.attributes().put(LOGGED_ATTRIBUTE, true));
    var body = codedBody.orElseGet(() -> errorResponseFactory.forFailedRequest(status, failure));
    errorResponseFactory.addRequestIds(
        body,
        MDC.get(LoggingContextConstants.TRACE_ID),
        MDC.get(LoggingContextConstants.CORRELATION_ID));
    return body;
  }

  /** Returns the status Spring Boot gives the failed request. */
  private HttpStatusCode statusOf(ServerRequest request) {
    var bootAttributes =
        super.getErrorAttributes(request, ErrorAttributeOptions.of(Include.STATUS));
    return HttpStatusCode.valueOf((Integer) bootAttributes.get(STATUS));
  }
}
