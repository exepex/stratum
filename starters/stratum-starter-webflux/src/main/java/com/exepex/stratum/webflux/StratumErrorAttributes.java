package com.exepex.stratum.webflux;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.web.ErrorResponseFactory;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.error.ErrorAttributeOptions.Include;
import org.springframework.boot.webflux.error.DefaultErrorAttributes;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.reactive.function.server.ServerRequest;

/**
 * Gives every request that failed outside the service's own exception handlers Stratum's error body
 * instead of Spring Boot's: a {@code StratumException} with the status mapped to its code, an
 * unknown path, a failed validation, an exception with {@code @ResponseStatus} or an unexpected
 * exception. Spring Boot's error handler writes it as JSON for an API caller and passes it to the
 * error page of a browser.
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

  /** Makes the error body of the failed request, and logs a {@code StratumException}. */
  private ProblemDetail bodyOf(ServerRequest request) {
    var failure = getError(request);
    var body =
        failure instanceof StratumException stratumFailure
            ? errorResponseFactory.forStratumException(stratumFailure)
            : errorResponseFactory.forFailedRequest(statusOf(request), failure);
    errorResponseFactory.addRequestIds(
        body,
        MDC.get(LoggingContextConstants.TRACE_ID),
        MDC.get(LoggingContextConstants.CORRELATION_ID));
    return body;
  }

  /**
   * Returns the status Spring Boot gives the failed request, or {@code 413} when Spring refused a
   * body over one of its own limits, such as a multipart part over its disk limit, which Spring
   * Boot answers with {@code 500}.
   */
  private HttpStatusCode statusOf(ServerRequest request) {
    if (getError(request) instanceof DataBufferLimitException) {
      return HttpStatus.CONTENT_TOO_LARGE;
    }
    var bootAttributes =
        super.getErrorAttributes(request, ErrorAttributeOptions.of(Include.STATUS));
    return HttpStatusCode.valueOf((Integer) bootAttributes.get(STATUS));
  }
}
