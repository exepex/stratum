package com.exepex.stratum.webmvc;

import com.exepex.stratum.web.RequestDeadline;
import com.exepex.stratum.web.ResponseTimeout;
import com.exepex.stratum.web.ResponseTimeouts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Gives the request the limit of its handler's {@link ResponseTimeout} once Spring MVC has chosen
 * the handler, in place of the limit {@link ResponseTimeoutFilter} started it with. It registers
 * itself with Spring MVC.
 */
@RequiredArgsConstructor
public class ResponseTimeoutInterceptor implements HandlerInterceptor, WebMvcConfigurer {

  /** Reads the handler's limit. */
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
    if (request.getAttribute(ResponseTimeoutFilter.DEADLINE_ATTRIBUTE)
        instanceof RequestDeadline deadline) {
      responseTimeouts.applyHandlerLimit(deadline, handler);
    }
    return true;
  }
}
