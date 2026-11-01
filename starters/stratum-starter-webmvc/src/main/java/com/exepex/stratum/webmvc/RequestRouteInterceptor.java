package com.exepex.stratum.webmvc;

import com.exepex.stratum.core.error.LoggingContextConstants;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

/**
 * Adds the route the request matched, such as {@code /orders/{orderId}}, to the logging context
 * once Spring MVC has chosen the handler, so the handler's log lines carry it. {@link
 * RequestFieldsFilter} removes it with the other request fields. It also keeps the route of the
 * service's own handler on the request, for the error page, which runs in a second dispatch where
 * Spring MVC matches its own error route. It registers itself with Spring MVC.
 */
public class RequestRouteInterceptor implements HandlerInterceptor, WebMvcConfigurer {

  /**
   * The request attribute that keeps the route of the service's handler. A static resource's
   * catch-all route, which answers an unknown path, is not kept.
   */
  static final String ROUTE_ATTRIBUTE = RequestRouteInterceptor.class.getName() + ".route";

  /**
   * Registers this interceptor for every path, first, so that the route is known even when a
   * service interceptor rejects the request.
   *
   * @param registry the interceptors Spring MVC calls
   */
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(this).order(Ordered.HIGHEST_PRECEDENCE);
  }

  /**
   * Puts the matched route in the logging context, and keeps the route of the service's handler on
   * the request.
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
      if (request.getDispatcherType() == DispatcherType.REQUEST
          && !(handler instanceof ResourceHttpRequestHandler)) {
        request.setAttribute(ROUTE_ATTRIBUTE, route);
      }
    }
    return true;
  }
}
