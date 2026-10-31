package com.exepex.stratum.webflux;

import com.exepex.stratum.core.error.LoggingContextConstants;
import java.util.List;
import java.util.function.Predicate;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.util.function.SingletonSupplier;
import org.springframework.web.reactive.HandlerAdapter;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.HandlerResult;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import reactor.core.publisher.Mono;

/**
 * Adds the route the request matched, such as {@code /orders/{orderId}}, to the logging context
 * just before Spring WebFlux calls the handler, so the handler's log lines carry it. Spring WebFlux
 * has no interceptors, so this wraps its own handler adapters: it runs first, puts the route, and
 * hands the request to the adapter that would have handled it.
 */
public class RequestRouteHandlerAdapter implements HandlerAdapter, Ordered {

  /**
   * Spring WebFlux's own handler adapters, which call the handlers, in their order and without this
   * one. They are looked up on the first request, once every adapter exists, and then kept.
   */
  private final SingletonSupplier<List<HandlerAdapter>> springAdapters;

  /**
   * Makes the adapter.
   *
   * @param handlerAdapters every handler adapter in the application, this one included
   */
  public RequestRouteHandlerAdapter(ObjectProvider<HandlerAdapter> handlerAdapters) {
    this.springAdapters =
        SingletonSupplier.of(
            () -> handlerAdapters.orderedStream().filter(Predicate.not(this::equals)).toList());
  }

  /**
   * Returns whether one of Spring WebFlux's adapters handles the handler.
   *
   * @param handler the chosen handler
   * @return whether it is handled
   */
  @Override
  public boolean supports(Object handler) {
    return springAdapters.obtain().stream().anyMatch(adapter -> adapter.supports(handler));
  }

  /**
   * Puts the matched route in the logging context, then lets Spring's adapter call the handler.
   *
   * @param exchange the request and its response
   * @param handler the chosen handler
   * @return the handler's result
   */
  @Override
  public Mono<HandlerResult> handle(ServerWebExchange exchange, Object handler) {
    if (exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)
        instanceof PathPattern route) {
      MDC.put(LoggingContextConstants.HTTP_ROUTE, route.getPatternString());
    }
    return springAdapters.obtain().stream()
        .filter(adapter -> adapter.supports(handler))
        .findFirst()
        .orElseThrow()
        .handle(exchange, handler);
  }

  /**
   * Returns the highest precedence, so Spring WebFlux asks this adapter first.
   *
   * @return the order
   */
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }
}
