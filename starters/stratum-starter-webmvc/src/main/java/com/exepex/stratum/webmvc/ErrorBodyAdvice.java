package com.exepex.stratum.webmvc;

import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.web.ErrorResponseFactory;
import com.exepex.stratum.web.WebConstants;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.MethodParameter;
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
 * application/problem+json}, as every other error has.
 */
@ControllerAdvice
@ConditionalOnStratumModule(WebConstants.MODULE)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class ErrorBodyAdvice implements ResponseBodyAdvice<Object> {

  /** Adds the IDs. */
  private final ErrorResponseFactory errorResponseFactory;

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
   * Adds the IDs from the logging context to a {@link ProblemDetail} body, and sets the media type
   * of the error controller's JSON body.
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
    } else if (isStratumErrorBody(request)
        && MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
      response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }
    return body;
  }

  /**
   * Returns whether the body is the one {@link StratumErrorAttributes} made. A body from the
   * service's own error attributes or error controller keeps its media type.
   */
  private boolean isStratumErrorBody(ServerHttpRequest request) {
    return request instanceof ServletServerHttpRequest servletRequest
        && servletRequest
                .getServletRequest()
                .getAttribute(StratumErrorAttributes.STRATUM_BODY_ATTRIBUTE)
            != null;
  }
}
