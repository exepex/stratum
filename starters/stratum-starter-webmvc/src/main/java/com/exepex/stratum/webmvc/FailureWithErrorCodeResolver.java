package com.exepex.stratum.webmvc;

import com.exepex.stratum.core.error.FailureWithErrorCode;
import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.web.ErrorResponseFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.accept.HeaderContentNegotiationStrategy;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers a {@link StratumException}, or another exception that implements {@link
 * FailureWithErrorCode}, thrown by a controller with the status mapped to its code: Stratum's error
 * body for an API caller, the HTML error page for a browser. It runs right after the service's
 * {@code @ExceptionHandler} methods, so they win, and before Spring MVC's status resolvers, so the
 * code's status wins over an {@code @ResponseStatus} on the exception or a {@code
 * ResponseStatusException}, as on WebFlux.
 */
@RequiredArgsConstructor
public class FailureWithErrorCodeResolver implements HandlerExceptionResolver, WebMvcConfigurer {

  /** Reads the media types the caller accepts from its {@code Accept} header. */
  private static final HeaderContentNegotiationStrategy ACCEPTED_MEDIA_TYPES =
      new HeaderContentNegotiationStrategy();

  /** Makes the error body and logs the failure. */
  private final ErrorResponseFactory errorResponseFactory;

  /** Writes the error body as JSON, as Spring MVC writes every other body. */
  private final JsonMapper jsonMapper;

  /**
   * Answers a failure with an error code, and leaves any other failure to the next resolver. A
   * caller that asks for HTML, such as a browser, is sent to the error page, with the same status.
   * The body holds no {@code instance}, because the request path can hold personal data.
   *
   * @param request the request
   * @param response the response
   * @param handler the controller method that failed, if known
   * @param failure what it failed with
   * @return an empty model when answered, or {@code null} to leave the failure to the next resolver
   */
  @Override
  public @Nullable ModelAndView resolveException(
      HttpServletRequest request,
      HttpServletResponse response,
      @Nullable Object handler,
      Exception failure) {
    return errorResponseFactory
        .forFailureWithErrorCode(failure)
        .map(body -> answer(request, response, body))
        .orElse(null);
  }

  /**
   * Places this resolver right after Spring MVC's {@code @ExceptionHandler} resolver, or first if
   * the service removed it.
   *
   * @param resolvers Spring MVC's resolvers, in the order they run
   */
  @Override
  public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
    var exceptionHandlers =
        resolvers.stream()
            .filter(ExceptionHandlerExceptionResolver.class::isInstance)
            .findFirst()
            .map(resolvers::indexOf)
            .orElse(-1);
    resolvers.add(exceptionHandlers + 1, this);
  }

  /** Sends the error page to a browser, or writes the body with the request's IDs. */
  private ModelAndView answer(
      HttpServletRequest request, HttpServletResponse response, ProblemDetail body) {
    try {
      if (asksForHtml(request)) {
        response.sendError(body.getStatus());
      } else {
        errorResponseFactory.addRequestIds(
            body,
            MDC.get(LoggingContextConstants.TRACE_ID),
            MDC.get(LoggingContextConstants.CORRELATION_ID));
        response.setStatus(body.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), errorResponseFactory.membersOf(body));
      }
    } catch (IOException writeFailure) {
      throw new UncheckedIOException(writeFailure);
    }
    return new ModelAndView();
  }

  /** Returns whether the caller asks for HTML, as a browser does; an invalid header does not. */
  private boolean asksForHtml(HttpServletRequest request) {
    try {
      return ACCEPTED_MEDIA_TYPES.resolveMediaTypes(new ServletWebRequest(request)).stream()
          .anyMatch(MediaType.TEXT_HTML::equalsTypeAndSubtype);
    } catch (HttpMediaTypeNotAcceptableException invalidHeader) {
      return false;
    }
  }
}
