package com.exepex.stratum.webmvc;

import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.web.RequestDeadline;
import com.exepex.stratum.web.ResponseTimeout;
import com.exepex.stratum.web.ResponseTimeouts;
import com.exepex.stratum.web.WebConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Keeps the handler's part of a request's limit. Once Spring MVC has chosen the handler, it gives
 * the request the limit of the handler's {@link ResponseTimeout}, in place of the limit {@link
 * ResponseTimeoutFilter} started it with. When the handler answers, before its body or view is
 * written, it ends the limit; if the time had already run out, it clears the interrupt and fails
 * the request with the timeout status instead, as WebFlux does. Writing on an interrupted thread
 * would make the server close the connection, so the caller would get no answer at all. It
 * registers itself with Spring MVC.
 */
@ControllerAdvice
@ConditionalOnStratumModule(WebConstants.MODULE)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class ResponseTimeoutInterceptor
    implements HandlerInterceptor, WebMvcConfigurer, ResponseBodyAdvice<Object> {

  /** Reads the handler's limit and makes the timeout failure. */
  private final ResponseTimeouts responseTimeouts;

  /**
   * Registers this interceptor for every path.
   *
   * @param registry the interceptors Spring MVC calls
   */
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(this);
  }

  /**
   * Gives the request its handler's limit, if the handler has one.
   *
   * @param request the request
   * @param response the response
   * @param handler the chosen handler
   * @return {@code true}, so the request goes on
   */
  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (deadlineOf(request) instanceof RequestDeadline deadline) {
      responseTimeouts.applyHandlerLimit(deadline, handler);
    }
    return true;
  }

  /**
   * Fails a request that ran out of time before its view is rendered, or after a handler that wrote
   * nothing yet.
   *
   * @param request the request
   * @param response the response
   * @param handler the handler
   * @param modelAndView the view to render, if any
   */
  @Override
  public void postHandle(
      HttpServletRequest request,
      HttpServletResponse response,
      Object handler,
      @Nullable ModelAndView modelAndView) {
    if (!response.isCommitted()) {
      endOrFail(request);
    }
  }

  /**
   * Returns {@code true}: every body is checked.
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
   * Fails a request that ran out of time before its body is written.
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
    if (request instanceof ServletServerHttpRequest servletRequest) {
      endOrFail(servletRequest.getServletRequest());
    }
    return body;
  }

  /**
   * Ends the request's limit, as its response is about to start. If the time had run out, it clears
   * the interrupt and throws the timeout failure, which Spring MVC answers with its status.
   */
  private void endOrFail(HttpServletRequest request) {
    if (deadlineOf(request) instanceof RequestDeadline deadline) {
      deadline.end();
      if (deadline.hasExpired()) {
        Thread.interrupted();
        var timedOut = responseTimeouts.timedOut(deadline);
        request.setAttribute(ResponseTimeoutFilter.TIMED_OUT_ATTRIBUTE, timedOut);
        throw timedOut;
      }
    }
  }

  /** Returns the request's deadline, which the filter put on it. */
  private @Nullable Object deadlineOf(HttpServletRequest request) {
    return request.getAttribute(ResponseTimeoutFilter.DEADLINE_ATTRIBUTE);
  }
}
