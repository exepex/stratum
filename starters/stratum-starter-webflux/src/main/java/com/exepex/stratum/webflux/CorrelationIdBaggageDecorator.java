package com.exepex.stratum.webflux;

import com.exepex.stratum.web.CorrelationIdChooser;
import com.exepex.stratum.web.WebConstants;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.HttpHandlerDecoratorFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * Writes a caller's correlation ID into a reactive request's W3C {@code baggage} header before
 * tracing reads the header, so the ID is the request's own baggage everywhere its work goes: its
 * log lines, the services it calls and Spring Boot's error handler. {@link
 * CorrelationIdChooser#rewrittenBaggage} decides the entries.
 */
@RequiredArgsConstructor
public class CorrelationIdBaggageDecorator implements HttpHandlerDecoratorFactory {

  /** Chooses the ID from the caller's header and baggage. */
  private final CorrelationIdChooser correlationIdChooser;

  /**
   * Returns the handler that rewrites each request's baggage, then lets Spring WebFlux handle it.
   *
   * @param httpHandler Spring WebFlux's handler, which starts tracing
   * @return the handler
   */
  @Override
  public HttpHandler apply(HttpHandler httpHandler) {
    return (request, response) -> httpHandler.handle(withCorrelationBaggage(request), response);
  }

  /** Returns the request with the rewritten baggage, or as it came when it needs no change. */
  private ServerHttpRequest withCorrelationBaggage(ServerHttpRequest request) {
    return correlationIdChooser
        .rewrittenBaggage(
            request.getHeaders().getFirst(WebConstants.CORRELATION_ID_HEADER),
            request.getHeaders().getValuesAsList(WebConstants.BAGGAGE_HEADER))
        .map(
            entries ->
                request.mutate().headers(headers -> replaceBaggage(headers, entries)).build())
        .orElse(request);
  }

  /** Replaces the baggage header with the entries, or removes it when there are none. */
  private void replaceBaggage(HttpHeaders headers, List<String> entries) {
    if (entries.isEmpty()) {
      headers.remove(WebConstants.BAGGAGE_HEADER);
    } else {
      headers.set(WebConstants.BAGGAGE_HEADER, String.join(",", entries));
    }
  }
}
