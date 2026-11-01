package com.exepex.stratum.webmvc;

import com.exepex.stratum.core.error.FailureWithErrorCode;
import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.web.ErrorResponseFactory;
import com.exepex.stratum.web.RequestDeadline;
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
 * code's status wins over an {@code @ResponseStatus} on the exception, as on WebFlux. A failure
 * whose code is only in one of its causes is answered last, after Spring MVC's resolvers, so that a
 * wrapper with a status of its own, such as a {@code ResponseStatusException} or unreadable JSON,
 * keeps Spring's answer, and a plain wrapper gets the code's status instead of 500.
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
   * Answers a failure that has an error code itself, and leaves any other failure to the next
   * resolver. A caller that asks for HTML, such as a browser, is sent to the error page, with the
   * same status. The body holds no {@code instance}, because the request path can hold personal
   * data.
   *
   * @param request the request
   * @param response the response
   * @param handler the controller method that failed, if known
   * @param failure what it failed with
   * @return an empty model when answered, or {@code null} to leave the failure to the next
   *     resolver, or to the timeout status when the request ran out of time
   */
  @Override
  public @Nullable ModelAndView resolveException(
      HttpServletRequest request,
      HttpServletResponse response,
      @Nullable Object handler,
      Exception failure) {
    return failure instanceof FailureWithErrorCode
        ? resolveFailureOrCause(request, response, failure)
        : null;
  }

  /**
   * Places this resolver right after Spring MVC's {@code @ExceptionHandler} resolver, or first if
   * the service removed it, and adds the resolver of a code in a cause after all the others.
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
    resolvers.add(
        (request, response, handler, failure) -> resolveFailureOrCause(request, response, failure));
  }

  /**
   * Answers a failure with an error code, itself or in one of its causes, unless the request ran
   * out of time. That request is left to {@link ResponseTimeoutFilter}, which answers with the
   * timeout status and clears the interrupt; writing on the interrupted thread would close the
   * connection unanswered.
   */
  private @Nullable ModelAndView resolveFailureOrCause(
      HttpServletRequest request, HttpServletResponse response, Exception failure) {
    if (hasRunOutOfTime(request)) {
      return null;
    }
    return errorResponseFactory
        .forFailureWithErrorCode(failure)
        .map(body -> answer(request, response, body))
        .orElse(null);
  }

  /** Returns whether the request ran out of its time limit before it failed. */
  private boolean hasRunOutOfTime(HttpServletRequest request) {
    return request.getAttribute(ResponseTimeoutFilter.DEADLINE_ATTRIBUTE)
            instanceof RequestDeadline deadline
        && deadline.hasExpired();
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
