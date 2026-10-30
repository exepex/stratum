package com.exepex.stratum.webmvc;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.web.ErrorResponseFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.accept.HeaderContentNegotiationStrategy;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * Answers a {@link StratumException} thrown by a controller with the status mapped to its code:
 * Stratum's error body for an API caller, the HTML error page for a browser. It runs last, so the
 * service's own handlers win.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class StratumExceptionHandler {

  /** Reads the media types the caller accepts from its {@code Accept} header. */
  private static final HeaderContentNegotiationStrategy ACCEPTED_MEDIA_TYPES =
      new HeaderContentNegotiationStrategy();

  /** Makes the error body and logs the failure. */
  private final ErrorResponseFactory errorResponseFactory;

  /**
   * Returns the error body of the exception, which holds its status. A caller that asks for HTML,
   * such as a browser, is sent to the error page instead, with the same status. The body is a map,
   * like the error controller's, because Spring MVC would add the request path to a {@link
   * org.springframework.http.ProblemDetail} as its {@code instance}, and a path can hold personal
   * data.
   *
   * @param failure the exception a controller threw
   * @param request the request
   * @param response the response
   * @return the status and members of the error body, or {@code null} when the error page answers
   * @throws IOException if the error page cannot be started
   * @throws HttpMediaTypeNotAcceptableException if the {@code Accept} header is not valid
   */
  @ExceptionHandler(StratumException.class)
  public @Nullable ResponseEntity<Map<String, Object>> answerStratumException(
      StratumException failure, HttpServletRequest request, HttpServletResponse response)
      throws IOException, HttpMediaTypeNotAcceptableException {
    var body = errorResponseFactory.forStratumException(failure);
    if (asksForHtml(request)) {
      response.sendError(body.getStatus());
      return null;
    }
    errorResponseFactory.addRequestIds(
        body,
        MDC.get(LoggingContextConstants.TRACE_ID),
        MDC.get(LoggingContextConstants.CORRELATION_ID));
    request.setAttribute(StratumErrorAttributes.STRATUM_BODY_ATTRIBUTE, true);
    return ResponseEntity.status(body.getStatus()).body(errorResponseFactory.membersOf(body));
  }

  /** Returns whether the caller asks for HTML, as a browser does. */
  private boolean asksForHtml(HttpServletRequest request)
      throws HttpMediaTypeNotAcceptableException {
    return ACCEPTED_MEDIA_TYPES.resolveMediaTypes(new ServletWebRequest(request)).stream()
        .anyMatch(MediaType.TEXT_HTML::equalsTypeAndSubtype);
  }
}
