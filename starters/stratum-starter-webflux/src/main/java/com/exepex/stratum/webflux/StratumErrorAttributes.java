package com.exepex.stratum.webflux;

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
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.reactive.function.server.ServerRequest;

/**
 * Gives every request that failed outside the service's own exception handlers Stratum's error body
 * instead of Spring Boot's: a {@code StratumException}, or another exception that implements {@code
 * FailureWithErrorCode}, with the status mapped to its code, an unknown path, a failed validation,
 * an exception with {@code @ResponseStatus} or an unexpected exception, when {@link
 * ErrorResponseFactory#answersWithStandardBody} says so; otherwise Spring Boot's own body, as the
 * service gave before Stratum. Spring Boot's error handler writes it as JSON for an API caller and
 * passes it to the error page of a browser.
 */
@RequiredArgsConstructor
public class StratumErrorAttributes extends DefaultErrorAttributes {

  /** The member of Spring Boot's error attributes that holds the status. */
  private static final String STATUS = "status";

  /** The member of Spring Boot's error attributes that holds the status's reason phrase. */
  private static final String ERROR = "error";

  /**
   * The request attribute that keeps Stratum's body once made, so that a second read, as when a
   * browser's page falls back to the JSON body, does not log the failure again. Only a request that
   * got Stratum's body has it.
   */
  static final String BODY_ATTRIBUTE = StratumErrorAttributes.class.getName() + ".body";

  /**
   * The request attribute that marks a failure with an error code as logged by Stratum, so Spring
   * Boot's error handler does not log it again.
   */
  static final String LOGGED_ATTRIBUTE = StratumErrorAttributes.class.getName() + ".logged";

  /** Makes the error body. */
  private final ErrorResponseFactory errorResponseFactory;

  /**
   * Returns Stratum's error body for the failed request, with its IDs, made once per request, or
   * Spring Boot's body. The options of {@code spring.web.error.include-*} apply only to Spring
   * Boot's: Stratum's never holds a message, stack trace or path.
   *
   * @param request the failed request
   * @param options what Spring Boot's body includes
   * @return the members of the error body
   */
  @Override
  public Map<String, Object> getErrorAttributes(
      ServerRequest request, ErrorAttributeOptions options) {
    var madeBody = request.attribute(BODY_ATTRIBUTE).map(ProblemDetail.class::cast);
    if (madeBody.isEmpty() && !answersWithStandardBody(request)) {
      return bootAttributes(request, options);
    }
    var body = madeBody.orElseGet(() -> bodyOf(request));
    request.attributes().put(BODY_ATTRIBUTE, body);
    return errorResponseFactory.membersOf(body);
  }

  /**
   * Makes the error body of the failed request, and logs a failure with an error code. A code in a
   * cause is used only when the wrapper has no status of its own, as on the servlet stack: a {@code
   * ResponseStatusException}, unreadable JSON or an exception with {@code @ResponseStatus} keeps
   * Spring's answer, even an explicit 500.
   */
  private ProblemDetail bodyOf(ServerRequest request) {
    var failure = getError(request);
    var codedBody =
        failure instanceof FailureWithErrorCode || !declaresStatus(failure)
            ? errorResponseFactory.forFailureWithErrorCode(failure)
            : Optional.<ProblemDetail>empty();
    codedBody.ifPresent(answered -> request.attributes().put(LOGGED_ATTRIBUTE, true));
    var body =
        codedBody.orElseGet(
            () -> errorResponseFactory.forFailedRequest(statusOf(request), failure));
    errorResponseFactory.addRequestIds(
        body,
        MDC.get(LoggingContextConstants.TRACE_ID),
        MDC.get(LoggingContextConstants.CORRELATION_ID));
    return body;
  }

  /**
   * Returns whether the request gets Stratum's body: as the factory decides, or because a code in a
   * cause answers it, which is Stratum's own error as on the servlet stack.
   */
  private boolean answersWithStandardBody(ServerRequest request) {
    var failure = getError(request);
    return errorResponseFactory.answersWithStandardBody(
            failure, request.headers().header(HttpHeaders.ACCEPT))
        || (!declaresStatus(failure) && errorResponseFactory.carriesErrorCode(failure));
  }

  /**
   * Returns whether the failure sets its own status: it is an {@link ErrorResponse}, which Spring
   * MVC answers with its status, or it has {@link ResponseStatus}. Spring Boot's WebFlux error
   * handling reads the status only from a {@code ResponseStatusException} or {@code
   * ResponseStatus}, but any {@code ErrorResponse} counts here so that both stacks keep the
   * wrapper's answer.
   */
  private boolean declaresStatus(Throwable failure) {
    return failure instanceof ErrorResponse
        || MergedAnnotations.from(failure.getClass(), SearchStrategy.TYPE_HIERARCHY)
            .isPresent(ResponseStatus.class);
  }

  /** Returns the status the failed request is answered with. */
  private HttpStatusCode statusOf(ServerRequest request) {
    return HttpStatusCode.valueOf(
        (Integer) bootAttributes(request, ErrorAttributeOptions.of(Include.STATUS)).get(STATUS));
  }

  /**
   * Returns Spring Boot's error attributes, with the status of a failure that is an {@link
   * ErrorResponse}, such as {@code ErrorResponseException}. Spring Boot's WebFlux error handling
   * reads the status only from a {@code ResponseStatusException} or {@link ResponseStatus}, and
   * would answer 500, where Spring MVC answers with the failure's own status.
   */
  private Map<String, Object> bootAttributes(ServerRequest request, ErrorAttributeOptions options) {
    var attributes = super.getErrorAttributes(request, options);
    if (getError(request) instanceof ErrorResponse errorResponse
        && attributes.containsKey(STATUS)) {
      var status = errorResponse.getStatusCode();
      attributes.put(STATUS, status.value());
      if (attributes.containsKey(ERROR)) {
        attributes.put(ERROR, reasonPhraseOf(status));
      }
    }
    return attributes;
  }

  /** Returns the status's reason phrase, or Spring MVC's "Http Status 499" for an unlisted one. */
  private String reasonPhraseOf(HttpStatusCode status) {
    return Optional.ofNullable(HttpStatus.resolve(status.value()))
        .map(HttpStatus::getReasonPhrase)
        .orElse("Http Status " + status.value());
  }
}
