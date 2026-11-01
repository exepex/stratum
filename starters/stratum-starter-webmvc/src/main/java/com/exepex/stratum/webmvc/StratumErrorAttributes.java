package com.exepex.stratum.webmvc;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.web.ErrorResponseFactory;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.error.ErrorAttributeOptions.Include;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;

/**
 * Gives a request that failed outside a controller's handling, such as an unknown path, a failed
 * validation, an exception with {@code @ResponseStatus} or an unexpected exception, Stratum's error
 * body instead of Spring Boot's, when {@link ErrorResponseFactory#answersWithStandardBody} says so;
 * otherwise Spring Boot's own body, as the service gave before Stratum. Spring Boot's error
 * controller writes it as JSON for an API caller and passes it to the error page of a browser.
 *
 * <p>The error controller runs in a second dispatch, after tracing has closed the request's scope,
 * so the request's IDs come from {@link RequestIdsFilter}.
 */
@RequiredArgsConstructor
public class StratumErrorAttributes extends DefaultErrorAttributes {

  /** The member of Spring Boot's error attributes that holds the status. */
  private static final String STATUS = "status";

  /**
   * The request attribute that marks a body as Stratum's, so that only Stratum's bodies are given
   * the Problem Details media type.
   */
  static final String STRATUM_BODY_ATTRIBUTE = StratumErrorAttributes.class.getName() + ".body";

  /**
   * The request attribute that marks a body as Spring Boot's, kept in backward-compatible mode, so
   * that it is counted when it is written as JSON to an API caller.
   */
  static final String BOOT_BODY_ATTRIBUTE = StratumErrorAttributes.class.getName() + ".boot-body";

  /** Makes the error body. */
  private final ErrorResponseFactory errorResponseFactory;

  /**
   * Returns Stratum's error body for the failed request, with its IDs, or Spring Boot's body. The
   * options of {@code spring.web.error.include-*} apply only to Spring Boot's: Stratum's never
   * holds a message, stack trace or path.
   *
   * @param webRequest the failed request
   * @param options what Spring Boot's body includes
   * @return the members of the error body
   */
  @Override
  public Map<String, Object> getErrorAttributes(
      WebRequest webRequest, ErrorAttributeOptions options) {
    var acceptHeaders =
        Optional.ofNullable(webRequest.getHeaderValues(HttpHeaders.ACCEPT))
            .map(List::of)
            .orElseGet(List::of);
    if (!errorResponseFactory.answersWithStandardBody(getError(webRequest), acceptHeaders)) {
      webRequest.setAttribute(BOOT_BODY_ATTRIBUTE, true, RequestAttributes.SCOPE_REQUEST);
      return super.getErrorAttributes(webRequest, options);
    }
    var bootAttributes =
        super.getErrorAttributes(webRequest, ErrorAttributeOptions.of(Include.STATUS));
    var status = HttpStatusCode.valueOf((Integer) bootAttributes.get(STATUS));
    var body = errorResponseFactory.forFailedRequest(status, getError(webRequest));
    errorResponseFactory.addRequestIds(
        body,
        requestId(
            webRequest, RequestIdsFilter.TRACE_ID_ATTRIBUTE, LoggingContextConstants.TRACE_ID),
        requestId(
            webRequest,
            RequestIdsFilter.CORRELATION_ID_ATTRIBUTE,
            LoggingContextConstants.CORRELATION_ID));
    webRequest.setAttribute(STRATUM_BODY_ATTRIBUTE, true, RequestAttributes.SCOPE_REQUEST);
    return errorResponseFactory.membersOf(body);
  }

  /**
   * Returns what the request failed with: for a request that ran out of time, the timeout, in place
   * of the failure the interrupt caused, and for a body a filter refused as too large, the refusal.
   *
   * @param webRequest the failed request
   * @return the failure, if known
   */
  @Override
  public @Nullable Throwable getError(WebRequest webRequest) {
    return Stream.of(
            ResponseTimeoutFilter.TIMED_OUT_ATTRIBUTE, RequestBodyLimitFilter.TOO_LARGE_ATTRIBUTE)
        .map(attribute -> webRequest.getAttribute(attribute, RequestAttributes.SCOPE_REQUEST))
        .filter(Throwable.class::isInstance)
        .map(Throwable.class::cast)
        .findFirst()
        .orElseGet(() -> super.getError(webRequest));
  }

  /** Returns an ID kept on the request, or else the one in the current logging context. */
  private @Nullable String requestId(WebRequest webRequest, String attribute, String mdcKey) {
    return Optional.ofNullable(webRequest.getAttribute(attribute, RequestAttributes.SCOPE_REQUEST))
        .map(Object::toString)
        .orElseGet(() -> MDC.get(mdcKey));
  }
}
