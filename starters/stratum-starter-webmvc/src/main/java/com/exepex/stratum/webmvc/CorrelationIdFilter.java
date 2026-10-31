package com.exepex.stratum.webmvc;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.web.CorrelationIdChooser;
import com.exepex.stratum.web.WebConstants;
import io.micrometer.tracing.Baggage;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives each request its correlation ID and returns it in the {@code X-Correlation-Id} response
 * header. When the ID is not already the one tracing would use, it is put in the tracing baggage
 * for the rest of the request, so every log line and every service called gets it.
 */
@RequiredArgsConstructor
public class CorrelationIdFilter extends OncePerRequestFilter {

  /** Runs just inside Spring Boot's tracing filter, whose order is one above the highest. */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 2;

  /** Reads the trace ID and the baggage, and sets the baggage. */
  private final Tracer tracer;

  /** Chooses the ID. */
  private final CorrelationIdChooser correlationIdChooser;

  /**
   * Chooses the request's correlation ID, returns it in the response header, and runs the rest of
   * the chain with it in the baggage when it has to be put there.
   *
   * @param request the request
   * @param response the response
   * @param filterChain the rest of the chain
   * @throws ServletException if the rest of the chain fails
   * @throws IOException if the rest of the chain fails to read or write
   */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    var callerHeader =
        new ServletServerHttpRequest(request)
            .getHeaders()
            .getFirst(WebConstants.CORRELATION_ID_HEADER);
    var baggage =
        Optional.ofNullable(tracer.getBaggage(LoggingContextConstants.CORRELATION_ID))
            .map(Baggage::get)
            .orElse(null);
    var traceId =
        Optional.ofNullable(tracer.currentSpan()).map(Span::context).map(TraceContext::traceId);
    var choice = correlationIdChooser.choose(callerHeader, baggage, traceId.orElse(null));
    choice.ifPresent(
        chosen -> response.setHeader(WebConstants.CORRELATION_ID_HEADER, chosen.correlationId()));
    if (choice.isEmpty() || !choice.get().setInBaggage()) {
      filterChain.doFilter(request, response);
      return;
    }
    try (var _ =
        tracer.createBaggageInScope(
            LoggingContextConstants.CORRELATION_ID, choice.get().correlationId())) {
      filterChain.doFilter(request, response);
    }
  }
}
