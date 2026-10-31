package com.exepex.stratum.webmvc;

import com.exepex.stratum.core.error.LoggingContextConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Adds the route the request matched, such as {@code /orders/{orderId}}, to the logging context
 * once Spring MVC has chosen the handler, so the handler's log lines carry it. {@link
 * RequestFieldsFilter} removes it with the other request fields. It registers itself with Spring
 * MVC.
 */
public class RequestRouteInterceptor implements HandlerInterceptor, WebMvcConfigurer {

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
   * Puts the matched route in the logging context.
   *
   * @param request the request
   * @param response the response
   * @param handler the chosen handler
   * @return {@code true}, so the request goes on
   */
  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)
        instanceof String route) {
      MDC.put(LoggingContextConstants.HTTP_ROUTE, route);
    }
    return true;
  }
}
