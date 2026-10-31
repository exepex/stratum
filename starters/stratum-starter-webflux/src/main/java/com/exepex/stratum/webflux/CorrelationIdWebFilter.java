package com.exepex.stratum.webflux;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.web.CorrelationIdChooser;
import com.exepex.stratum.web.WebConstants;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.tracing.Baggage;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.contextpropagation.reactor.ReactorBaggage;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * Gives each reactive request its correlation ID and returns it in the {@code X-Correlation-Id}
 * response header. When the ID is not already the one tracing would use, it is put in the tracing
 * baggage for the rest of the request, so every log line and every service called gets it.
 */
@RequiredArgsConstructor
public class CorrelationIdWebFilter implements WebFilter, Ordered {

  /** Runs before the request fields filter and the service's own filters. */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 2;

  /** Restores the request's tracing context from Reactor's context, to read the IDs. */
  private static final ContextSnapshotFactory SNAPSHOTS = ContextSnapshotFactory.builder().build();

  /** Reads the trace ID and the baggage. */
  private final Tracer tracer;

  /** Chooses the ID. */
  private final CorrelationIdChooser correlationIdChooser;

  /**
   * Chooses the request's correlation ID, returns it in the response header, and runs the rest of
   * the chain with it in the baggage when it has to be put there.
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

  /** Chooses the ID with the request's tracing context in place, then runs the chain. */
  private Mono<Void> filterWithCorrelationId(
      ServerWebExchange exchange, WebFilterChain chain, ContextView context) {
    var callerHeader =
        exchange.getRequest().getHeaders().getFirst(WebConstants.CORRELATION_ID_HEADER);
    Optional<CorrelationIdChooser.Choice> choice;
    try (var _ = SNAPSHOTS.setThreadLocalsFrom(context)) {
      var baggage =
          Optional.ofNullable(tracer.getBaggage(LoggingContextConstants.CORRELATION_ID))
              .map(Baggage::get)
              .orElse(null);
      var traceId =
          Optional.ofNullable(tracer.currentSpan())
              .map(Span::context)
              .map(TraceContext::traceId)
              .orElse(null);
      choice = correlationIdChooser.choose(callerHeader, baggage, traceId);
    }
    choice.ifPresent(
        chosen ->
            exchange
                .getResponse()
                .getHeaders()
                .set(WebConstants.CORRELATION_ID_HEADER, chosen.correlationId()));
    if (choice.isEmpty() || !choice.get().setInBaggage()) {
      return chain.filter(exchange);
    }
    return chain
        .filter(exchange)
        .contextWrite(
            ReactorBaggage.append(
                LoggingContextConstants.CORRELATION_ID, choice.get().correlationId()));
  }
}
