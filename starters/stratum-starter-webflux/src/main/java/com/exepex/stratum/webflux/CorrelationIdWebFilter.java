package com.exepex.stratum.webflux;

import com.exepex.stratum.web.CorrelationIdChooser;
import com.exepex.stratum.web.WebConstants;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.tracing.Tracer;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * Returns each reactive request's correlation ID in the {@code X-Correlation-Id} response header.
 * {@link CorrelationIdBaggageDecorator} has already put a caller's ID in the request's baggage, so
 * the ID is the baggage's, or the trace ID when there is none.
 */
@RequiredArgsConstructor
public class CorrelationIdWebFilter implements WebFilter, Ordered {

  /** Runs before the request fields filter and the service's own filters. */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 2;

  /** Restores the request's tracing context from Reactor's context, to read the IDs. */
  private static final ContextSnapshotFactory SNAPSHOTS = ContextSnapshotFactory.builder().build();

  /** Reads the trace ID and the baggage. */
  private final Tracer tracer;

  /** Reads the ID from the tracing context. */
  private final CorrelationIdChooser correlationIdChooser;

  /**
   * Returns the request's correlation ID in the response header, then runs the rest of the chain.
   *
   * @param exchange the request and its response
   * @param chain the rest of the chain
   * @return completes when the request has been answered
   */
  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    return Mono.deferContextual(context -> filterWithCorrelationId(exchange, chain, context));
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

  /** Reads the ID with the request's tracing context in place, then runs the chain. */
  private Mono<Void> filterWithCorrelationId(
      ServerWebExchange exchange, WebFilterChain chain, ContextView context) {
    Optional<String> correlationId;
    try (var _ = SNAPSHOTS.setThreadLocalsFrom(context)) {
      correlationId = correlationIdChooser.currentCorrelationId(tracer);
    }
    correlationId.ifPresent(
        id -> exchange.getResponse().getHeaders().set(WebConstants.CORRELATION_ID_HEADER, id));
    return chain.filter(exchange);
  }
}
