package com.exepex.stratum.webflux;

import com.exepex.stratum.web.RequestLogWriter;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
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
 * handler, outside the filters, is logged with the status it got. A request the caller cancels
 * before its response starts is logged with status 499. The line carries the request fields and
 * tracing IDs of the request's context. Static files served write none.
 */
@RequiredArgsConstructor
public class RequestLogWebFilter implements WebFilter, Ordered {

  /** Runs just inside the request fields filter, so the line carries the request fields. */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 5;

  /** The status of a request the caller cancelled before its response started, as nginx logs it. */
  private static final HttpStatusCode CLIENT_CLOSED_REQUEST = HttpStatusCode.valueOf(499);

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

  /**
   * Runs the chain, with the line written when the response is committed, or when the caller
   * cancels the request before then. Only the first of the two writes it.
   */
  private Mono<Void> filterAndLog(
      ServerWebExchange exchange, WebFilterChain chain, ContextView context) {
    var start = System.nanoTime();
    var requestContext = SNAPSHOTS.captureFrom(context);
    var written = new AtomicBoolean();
    exchange
        .getResponse()
        .beforeCommit(
            () ->
                Mono.fromRunnable(
                    () ->
                        write(written, exchange, start, requestContext, responseStatus(exchange))));
    return chain
        .filter(exchange)
        .doOnCancel(() -> write(written, exchange, start, requestContext, CLIENT_CLOSED_REQUEST));
  }

  /** Returns the status the response is sent with, which is 200 when none was set. */
  private HttpStatusCode responseStatus(ServerWebExchange exchange) {
    return Optional.ofNullable(exchange.getResponse().getStatusCode()).orElse(HttpStatus.OK);
  }

  /**
   * Writes the line with the request's context in place, unless it was written already or a static
   * file was served. A path no handler matched also goes to the static file handler, which answers
   * 404, so that is logged.
   */
  private void write(
      AtomicBoolean written,
      ServerWebExchange exchange,
      long start,
      ContextSnapshot requestContext,
      HttpStatusCode status) {
    if (!written.compareAndSet(false, true)
        || exchange.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE)
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
