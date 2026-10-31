package com.exepex.stratum.webflux;

import com.exepex.stratum.web.RequestFieldValues;
import com.exepex.stratum.web.RequestFieldsReader;
import com.exepex.stratum.web.WebConstants;
import java.net.InetSocketAddress;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * Gives every log line of a reactive request its fields: it puts them in Reactor's context, from
 * which Reactor restores them into the logging context (SLF4J's MDC) on whichever thread each step
 * runs. The route is added once Spring has matched the request. When browsers are asked for client
 * hints, it also adds the {@code Accept-CH} response header.
 */
@RequiredArgsConstructor
public class RequestFieldsWebFilter implements WebFilter, Ordered {

  /** Runs just inside the correlation ID filter, before the service's own filters. */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 4;

  /** The response header that asks browsers for client hints. */
  private static final String ACCEPT_CLIENT_HINTS = "Accept-CH";

  /** Reads the fields. */
  private final RequestFieldsReader requestFieldsReader;

  /**
   * Runs the rest of the chain with the request's fields in Reactor's context.
   *
   * @param exchange the request and its response
   * @param chain the rest of the chain
   * @return completes when the request has been answered
   */
  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    var request = exchange.getRequest();
    var fields =
        requestFieldsReader.fieldsOf(
            request.getMethod(),
            request.getURI(),
            request.getHeaders(),
            Optional.ofNullable(request.getRemoteAddress())
                .map(InetSocketAddress::getAddress)
                .orElse(null));
    requestFieldsReader
        .clientHintsToRequest()
        .ifPresent(
            clientHints ->
                exchange.getResponse().getHeaders().set(ACCEPT_CLIENT_HINTS, clientHints));
    var values = new RequestFieldValues(fields, () -> routeOf(exchange));
    return chain
        .filter(exchange)
        .contextWrite(Context.of(WebConstants.REQUEST_FIELDS_CONTEXT_KEY, values));
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

  /** Returns the route the request matched, once Spring has matched it. */
  private @Nullable String routeOf(ServerWebExchange exchange) {
    return exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)
            instanceof PathPattern route
        ? route.getPatternString()
        : null;
  }
}
