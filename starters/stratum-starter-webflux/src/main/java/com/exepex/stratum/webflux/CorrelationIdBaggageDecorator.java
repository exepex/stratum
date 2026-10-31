package com.exepex.stratum.webflux;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.web.CorrelationIdChooser;
import com.exepex.stratum.web.WebConstants;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.HttpHandlerDecoratorFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * Writes a reactive request's correlation ID into its W3C {@code baggage} header before tracing
 * reads the header, so the ID is the request's own baggage everywhere its work goes: its log lines,
 * the services it calls and Spring Boot's error handler. A caller's {@code X-Correlation-Id}
 * replaces the {@code correlationId} entry. When callers' IDs are not accepted, or the entry is not
 * safe for logs, the entry is removed and tracing uses the trace ID.
 */
@RequiredArgsConstructor
public class CorrelationIdBaggageDecorator implements HttpHandlerDecoratorFactory {

  /** The W3C header that carries the baggage. */
  private static final String BAGGAGE = "baggage";

  /** Separates a baggage entry's key and value. */
  private static final String KEY_VALUE_SEPARATOR = "=";

  /** Starts an entry's properties, which follow its value. */
  private static final String PROPERTIES_SEPARATOR = ";";

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

  /** Returns the request with the chosen ID as its baggage entry, or as it came when it has it. */
  private ServerHttpRequest withCorrelationBaggage(ServerHttpRequest request) {
    var baggageEntries = request.getHeaders().getValuesAsList(BAGGAGE);
    var callerBaggageId =
        baggageEntries.stream().flatMap(entry -> correlationIdOf(entry).stream()).findFirst();
    var choice =
        correlationIdChooser.choose(
            request.getHeaders().getFirst(WebConstants.CORRELATION_ID_HEADER),
            callerBaggageId.orElse(null),
            null);
    var baggageAlreadyRight =
        choice.map(chosen -> !chosen.setInBaggage()).orElse(callerBaggageId.isEmpty());
    if (baggageAlreadyRight) {
      return request;
    }
    var otherEntries = baggageEntries.stream().filter(entry -> correlationIdOf(entry).isEmpty());
    var chosenEntry =
        choice.map(
            chosen ->
                LoggingContextConstants.CORRELATION_ID
                    + KEY_VALUE_SEPARATOR
                    + chosen.correlationId());
    var entries = Stream.concat(otherEntries, chosenEntry.stream()).toList();
    return request.mutate().headers(headers -> replaceBaggage(headers, entries)).build();
  }

  /** Replaces the baggage header with the entries, or removes it when there are none. */
  private void replaceBaggage(HttpHeaders headers, List<String> entries) {
    if (entries.isEmpty()) {
      headers.remove(BAGGAGE);
    } else {
      headers.set(BAGGAGE, String.join(",", entries));
    }
  }

  /** Returns the value of a baggage entry whose key is {@code correlationId}, if it is one. */
  private Optional<String> correlationIdOf(String entry) {
    var keyAndValue = entry.split(PROPERTIES_SEPARATOR, 2)[0].split(KEY_VALUE_SEPARATOR, 2);
    return keyAndValue.length == 2
            && LoggingContextConstants.CORRELATION_ID.equals(keyAndValue[0].strip())
        ? Optional.of(keyAndValue[1].strip())
        : Optional.empty();
  }
}
