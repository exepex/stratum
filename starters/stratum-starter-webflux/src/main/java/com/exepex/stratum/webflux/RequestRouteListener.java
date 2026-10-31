package com.exepex.stratum.webflux;

import com.exepex.stratum.core.error.LoggingContextConstants;
import org.slf4j.MDC;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;

/**
 * Adds the route the request matched, such as {@code /orders/{orderId}}, to the logging context
 * just before the handler runs, so the handler's log lines carry it.
 */
public class RequestRouteListener implements HandlerStartListener {

  /**
   * Puts the matched route in the logging context.
   *
   * @param exchange the request and its response
   * @param handler the chosen handler
   */
  @Override
  public void onHandlerStart(ServerWebExchange exchange, Object handler) {
    if (exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)
        instanceof PathPattern route) {
      MDC.put(LoggingContextConstants.HTTP_ROUTE, route.getPatternString());
    }
  }
}
