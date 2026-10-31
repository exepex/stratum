package com.exepex.stratum.webflux;

import com.exepex.stratum.web.RequestFieldValues;
import com.exepex.stratum.web.RequestFieldsReader;
import com.exepex.stratum.web.WebConstants;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.ConnectionInformation;
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
            connectionAddressOf(request));
    requestFieldsReader
        .clientHintsToRequest()
        .ifPresent(
            clientHints ->
                exchange
                    .getResponse()
                    .getHeaders()
                    .set(WebConstants.ACCEPT_CLIENT_HINTS_HEADER, clientHints));
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

  /**
   * Returns the address of the connection the request came on. On Reactor Netty this is never taken
   * from a forwarded header, even when Spring Boot turns Netty's forwarded-header support on, as it
   * does on Kubernetes: Netty trusts the leftmost {@code X-Forwarded-For} entry, which a client can
   * write. Stratum's own check of the trusted proxies is then the only one.
   */
  private @Nullable InetAddress connectionAddressOf(ServerHttpRequest request) {
    var address =
        nativeRequestOf(request) instanceof ConnectionInformation connection
            ? connection.connectionRemoteAddress()
            : request.getRemoteAddress();
    return address instanceof InetSocketAddress socketAddress ? socketAddress.getAddress() : null;
  }

  /**
   * Returns the server's own request, or nothing for the mock request of a service's {@code
   * WebTestClient} test, such as a {@code @WebFluxTest}. The mock has the same base class as a
   * server's request and throws when asked for its own, so no type check can tell them apart.
   */
  private @Nullable Object nativeRequestOf(ServerHttpRequest request) {
    try {
      return ServerHttpRequestDecorator.getNativeRequest(request);
    } catch (IllegalStateException mockRequest) {
      return null;
    }
  }

  /** Returns the route the request matched, once Spring has matched it. */
  private @Nullable String routeOf(ServerWebExchange exchange) {
    return exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)
            instanceof PathPattern route
        ? route.getPatternString()
        : null;
  }
}
