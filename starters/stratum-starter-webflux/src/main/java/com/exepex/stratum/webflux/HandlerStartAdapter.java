package com.exepex.stratum.webflux;

import java.util.List;
import java.util.function.Predicate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.util.function.SingletonSupplier;
import org.springframework.web.reactive.HandlerAdapter;
import org.springframework.web.reactive.HandlerResult;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Tells every {@link HandlerStartListener} that Spring WebFlux has chosen the request's handler,
 * just before the handler runs. Spring WebFlux has no interceptors, so this wraps its own handler
 * adapters: it runs first, tells the listeners, and hands the request to the adapter that would
 * have handled it.
 */
public class HandlerStartAdapter implements HandlerAdapter, Ordered {

  /**
   * Spring WebFlux's own handler adapters, which call the handlers, in their order and without this
   * one. They are looked up on the first request, once every adapter exists, and then kept.
   */
  private final SingletonSupplier<List<HandlerAdapter>> springAdapters;

  /** The listeners, in their order, looked up on the first request and then kept. */
  private final SingletonSupplier<List<HandlerStartListener>> listeners;

  /**
   * Makes the adapter.
   *
   * @param handlerAdapters every handler adapter in the application, this one included
   * @param handlerStartListeners what is told when a handler is about to run
   */
  public HandlerStartAdapter(
      ObjectProvider<HandlerAdapter> handlerAdapters,
      ObjectProvider<HandlerStartListener> handlerStartListeners) {
    this.springAdapters =
        SingletonSupplier.of(
            () -> handlerAdapters.orderedStream().filter(Predicate.not(this::equals)).toList());
    this.listeners = SingletonSupplier.of(() -> handlerStartListeners.orderedStream().toList());
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
   * Tells the listeners, then lets Spring's adapter call the handler.
   *
   * @param exchange the request and its response
   * @param handler the chosen handler
   * @return the handler's result
   */
  @Override
  public Mono<HandlerResult> handle(ServerWebExchange exchange, Object handler) {
    listeners.obtain().forEach(listener -> listener.onHandlerStart(exchange, handler));
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
