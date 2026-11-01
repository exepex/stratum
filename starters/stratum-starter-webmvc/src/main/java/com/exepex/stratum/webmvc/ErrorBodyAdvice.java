package com.exepex.stratum.webmvc;

import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.web.BootErrorBodyCounter;
import com.exepex.stratum.web.ErrorResponseFactory;
import com.exepex.stratum.web.WebConstants;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Finishes every error body before it is written. It adds the request's trace and correlation IDs
 * to a {@link ProblemDetail}, Stratum's or the service's own, unless the body has them already, and
 * nothing else in the body changes. It also gives the JSON body that {@link StratumErrorAttributes}
 * made for Spring Boot's error controller the Problem Details media type, {@code
 * application/problem+json}, as every other error has, and counts each JSON body that kept Spring
 * Boot's format in backward-compatible mode, with the route of the service's handler.
 */
@ControllerAdvice
@ConditionalOnStratumModule(WebConstants.MODULE)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class ErrorBodyAdvice implements ResponseBodyAdvice<Object> {

  /** The body's member that holds the status of the answer. */
  private static final String STATUS = "status";

  /** Adds the IDs. */
  private final ErrorResponseFactory errorResponseFactory;

  /** Counts Spring Boot's bodies, when the service has Micrometer. */
  private final ObjectProvider<BootErrorBodyCounter> bootErrorBodyCounter;

  /**
   * Returns {@code true}: whether a body is an error body is only known when it is written.
   *
   * @param returnType the controller method's return type
   * @param converterType the converter that writes the body
   * @return {@code true}
   */
  @Override
  public boolean supports(
      MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
    return true;
  }

  /**
   * Adds the IDs from the logging context to a {@link ProblemDetail} body, sets the media type of
   * the error controller's JSON body, or counts it when it is Spring Boot's.
   *
   * @param body the body to write
   * @param returnType the controller method's return type
   * @param contentType the body's content type
   * @param converterType the converter that writes the body
   * @param request the request
   * @param response the response
   * @return the same body
   */
  @Override
  public @Nullable Object beforeBodyWrite(
      @Nullable Object body,
      MethodParameter returnType,
      MediaType contentType,
      Class<? extends HttpMessageConverter<?>> converterType,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    if (body instanceof ProblemDetail errorBody) {
      errorResponseFactory.addRequestIds(
          errorBody,
          MDC.get(LoggingContextConstants.TRACE_ID),
          MDC.get(LoggingContextConstants.CORRELATION_ID));
    } else if (request instanceof ServletServerHttpRequest servletRequest
        && MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
      finishErrorControllerBody(servletRequest.getServletRequest(), body, response);
    }
    return body;
  }

  /**
   * Gives the body that {@link StratumErrorAttributes} made the Problem Details media type, or
   * counts Spring Boot's body. A body from the service's own error attributes or error controller
   * has neither mark, keeps its media type and is not counted.
   */
  private void finishErrorControllerBody(
      HttpServletRequest servletRequest, @Nullable Object body, ServerHttpResponse response) {
    if (servletRequest.getAttribute(StratumErrorAttributes.STRATUM_BODY_ATTRIBUTE) != null) {
      answerWithBodyStatus(body, response);
      response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    } else if (servletRequest.getAttribute(StratumErrorAttributes.BOOT_BODY_ATTRIBUTE) != null) {
      answerWithBodyStatus(body, response);
      bootErrorBodyCounter.ifAvailable(
          counter ->
              counter.count(
                  (String) servletRequest.getAttribute(RequestRouteInterceptor.ROUTE_ATTRIBUTE)));
    }
  }

  /**
   * Answers with the body's status. Spring Boot's error controller answers 500 for a status that
   * {@code HttpStatus} does not list, such as 499, while the body keeps the thrown status.
   */
  private void answerWithBodyStatus(@Nullable Object body, ServerHttpResponse response) {
    if (body instanceof Map<?, ?> members && members.get(STATUS) instanceof Integer status) {
      response.setStatusCode(HttpStatusCode.valueOf(status));
    }
  }
}
