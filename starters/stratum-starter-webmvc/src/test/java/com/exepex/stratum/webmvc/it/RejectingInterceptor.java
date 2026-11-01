package com.exepex.stratum.webmvc.it;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * A service interceptor that rejects the caller of a matched route with {@code 401} before its
 * controller runs, as a team's own access check does.
 */
class RejectingInterceptor implements HandlerInterceptor, WebMvcConfigurer {

  /** The route the interceptor rejects. */
  static final String GUARDED_ROUTE = "/orders/guarded";

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(this).addPathPatterns(GUARDED_ROUTE);
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws IOException {
    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    return false;
  }
}
