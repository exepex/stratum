package com.exepex.stratum.webflux;

import org.springframework.web.server.ServerWebExchange;

/**
 * Is told, through {@link HandlerStartAdapter}, that Spring WebFlux has chosen a request's handler
 * and is about to run it, as a Spring MVC interceptor's {@code preHandle} is.
 */
@FunctionalInterface
public interface HandlerStartListener {

  /**
   * Called just before the handler runs.
   *
   * @param exchange the request and its response
   * @param handler the chosen handler
   */
  void onHandlerStart(ServerWebExchange exchange, Object handler);
}
