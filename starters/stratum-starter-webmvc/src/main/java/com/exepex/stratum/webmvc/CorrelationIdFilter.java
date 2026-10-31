package com.exepex.stratum.webmvc;

import com.exepex.stratum.web.CorrelationIdChooser;
import com.exepex.stratum.web.WebConstants;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Returns each request's correlation ID in the {@code X-Correlation-Id} response header. {@link
 * CorrelationIdBaggageFilter} has already put a caller's ID in the request's baggage, so the ID is
 * the baggage's, or the trace ID when there is none.
 */
@RequiredArgsConstructor
public class CorrelationIdFilter extends OncePerRequestFilter {

  /** Runs just inside Spring Boot's tracing filter, whose order is one above the highest. */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 2;

  /** Reads the trace ID and the baggage. */
  private final Tracer tracer;

  /** Reads the ID from the tracing context. */
  private final CorrelationIdChooser correlationIdChooser;

  /**
   * Returns the request's correlation ID in the response header, then runs the rest of the chain.
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
    correlationIdChooser
        .currentCorrelationId(tracer)
        .ifPresent(id -> response.setHeader(WebConstants.CORRELATION_ID_HEADER, id));
    filterChain.doFilter(request, response);
  }
}
