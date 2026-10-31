package com.exepex.stratum.webflux;

import com.exepex.stratum.web.RequestLogWriter;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.resource.ResourceWebHandler;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * Writes one log line when each reactive request's response starts, through {@link
 * RequestLogWriter}. It writes when the response is committed, so an error answered by the error
 * handler, outside the filters, is logged with the status it got. The line carries the request
 * fields and tracing IDs of the request's context. Static files served write none.
 */
@RequiredArgsConstructor
public class RequestLogWebFilter implements WebFilter, Ordered {

  /** Runs just inside the request fields filter, so the line carries the request fields. */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 5;

  /** Captures the request's context, to restore it when the line is written. */
  private static final ContextSnapshotFactory SNAPSHOTS = ContextSnapshotFactory.builder().build();

  /** Writes the line. */
  private final RequestLogWriter requestLogWriter;

  /**
   * Arranges for the line to be written when the response is committed, then runs the rest of the
   * chain.
   *
   * @param exchange the request and its response
   * @param chain the rest of the chain
   * @return completes when the request has been answered
   */
  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    if (!requestLogWriter.isLogged(
        exchange.getRequest().getPath().pathWithinApplication().value())) {
      return chain.filter(exchange);
    }
    return Mono.deferContextual(context -> filterAndLog(exchange, chain, context));
  }

  /**
   * Returns the order of this filter.
   *
   * @return {@link #ORDER}
   */
  @Override
  public int getOrder() {
    return ORDER;
  }

  /** Runs the chain, with the line written when the response is committed. */
  private Mono<Void> filterAndLog(
      ServerWebExchange exchange, WebFilterChain chain, ContextView context) {
    var start = System.nanoTime();
    var requestContext = SNAPSHOTS.captureFrom(context);
    exchange
        .getResponse()
        .beforeCommit(() -> Mono.fromRunnable(() -> write(exchange, start, requestContext)));
    return chain.filter(exchange);
  }

  /**
   * Writes the line with the request's context in place, unless a static file was served. A path no
   * handler matched also goes to the static file handler, which answers 404, so that is logged.
   */
  private void write(ServerWebExchange exchange, long start, ContextSnapshot requestContext) {
    var status = Optional.ofNullable(exchange.getResponse().getStatusCode()).orElse(HttpStatus.OK);
    if (exchange.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE)
            instanceof ResourceWebHandler
        && !status.isError()) {
      return;
    }
    try (var _ = requestContext.setThreadLocals()) {
      requestLogWriter.write(
          status.value(),
          Duration.ofNanos(System.nanoTime() - start),
          exchange.getRequest().getHeaders(),
          exchange.getResponse().getHeaders());
    }
  }
}
