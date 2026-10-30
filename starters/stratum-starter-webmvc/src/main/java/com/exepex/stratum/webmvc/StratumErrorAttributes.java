package com.exepex.stratum.webmvc;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.web.ErrorResponseFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.error.ErrorAttributeOptions.Include;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.ModelAndView;

/**
 * Gives a request that failed outside a controller's handling, such as an unknown path, a failed
 * validation, an exception with {@code @ResponseStatus} or an unexpected exception, Stratum's error
 * body instead of Spring Boot's. Spring Boot's error controller writes it as JSON for an API caller
 * and passes it to the error page of a browser.
 *
 * <p>The error controller runs in a second dispatch, after tracing has closed the request's scope,
 * so the request's IDs are kept on the request when the failure is first seen.
 */
@RequiredArgsConstructor
public class StratumErrorAttributes extends DefaultErrorAttributes {

  /** The member of Spring Boot's error attributes that holds the status. */
  private static final String STATUS = "status";

  /** The request attribute that keeps the request's trace ID for the error dispatch. */
  private static final String TRACE_ID_ATTRIBUTE =
      StratumErrorAttributes.class.getName() + "." + LoggingContextConstants.TRACE_ID;

  /** The request attribute that keeps the request's correlation ID for the error dispatch. */
  private static final String CORRELATION_ID_ATTRIBUTE =
      StratumErrorAttributes.class.getName() + "." + LoggingContextConstants.CORRELATION_ID;

  /** Makes the error body. */
  private final ErrorResponseFactory errorResponseFactory;

  /**
   * Returns Stratum's error body for the failed request, with its IDs. The options of {@code
   * spring.web.error.include-*} do not apply: the body never holds a message, stack trace or path.
   *
   * @param webRequest the failed request
   * @param options ignored
   * @return the members of the error body
   */
  @Override
  public Map<String, Object> getErrorAttributes(
      WebRequest webRequest, ErrorAttributeOptions options) {
    var bootAttributes =
        super.getErrorAttributes(webRequest, ErrorAttributeOptions.of(Include.STATUS));
    var status = HttpStatusCode.valueOf((Integer) bootAttributes.get(STATUS));
    var body = errorResponseFactory.forFailedRequest(status, getError(webRequest));
    errorResponseFactory.addRequestIds(
        body,
        requestId(webRequest, TRACE_ID_ATTRIBUTE, LoggingContextConstants.TRACE_ID),
        requestId(webRequest, CORRELATION_ID_ATTRIBUTE, LoggingContextConstants.CORRELATION_ID));
    return errorResponseFactory.membersOf(body);
  }

  /**
   * Keeps the request's IDs from the logging context on the request, while its scope is still open,
   * then records the failure as Spring Boot does.
   *
   * @param request the failed request
   * @param response the response
   * @param handler the handler that failed, if any
   * @param failure what it failed with
   * @return {@code null}, so the other resolvers answer the failure
   */
  @Override
  public @Nullable ModelAndView resolveException(
      HttpServletRequest request,
      HttpServletResponse response,
      @Nullable Object handler,
      Exception failure) {
    request.setAttribute(TRACE_ID_ATTRIBUTE, MDC.get(LoggingContextConstants.TRACE_ID));
    request.setAttribute(CORRELATION_ID_ATTRIBUTE, MDC.get(LoggingContextConstants.CORRELATION_ID));
    return super.resolveException(request, response, handler, failure);
  }

  /** Returns an ID kept on the request, or else the one in the current logging context. */
  private @Nullable String requestId(WebRequest webRequest, String attribute, String mdcKey) {
    return Optional.ofNullable(webRequest.getAttribute(attribute, RequestAttributes.SCOPE_REQUEST))
        .map(Object::toString)
        .orElseGet(() -> MDC.get(mdcKey));
  }
}
