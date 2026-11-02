package com.exepex.stratum.webmvc;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.web.CorrelationIdChooser;
import com.exepex.stratum.web.WebConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Writes a caller's correlation ID into a request's W3C {@code baggage} header before tracing reads
 * the header, so the ID is the request's own baggage everywhere its work goes: its log lines, the
 * services it calls, the asynchronous dispatch of a {@code Callable} or {@code DeferredResult}, and
 * Spring Boot's error page. {@link CorrelationIdChooser#rewrittenBaggage} decides the entries.
 */
@StratumInternal
@RequiredArgsConstructor
public class CorrelationIdBaggageFilter extends OncePerRequestFilter {

  /** Runs before Spring Boot's tracing filter, whose order is one above the highest. */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE;

  /** Chooses the ID from the caller's header and baggage. */
  private final CorrelationIdChooser correlationIdChooser;

  /**
   * Runs the rest of the chain with the rewritten baggage, or with the request as it came when its
   * baggage needs no change.
   *
   * @param request the request
   * @param response the response
   * @param filterChain the rest of the chain, which starts tracing
   * @throws ServletException if the rest of the chain fails
   * @throws IOException if the rest of the chain fails to read or write
   */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    var headers = new ServletServerHttpRequest(request).getHeaders();
    var rewrittenBaggage =
        correlationIdChooser.rewrittenBaggage(
            headers.getFirst(WebConstants.CORRELATION_ID_HEADER),
            headers.getValuesAsList(WebConstants.BAGGAGE_HEADER));
    filterChain.doFilter(
        rewrittenBaggage
            .<HttpServletRequest>map(entries -> new RewrittenBaggageRequest(request, entries))
            .orElse(request),
        response);
  }

  /** The request with its baggage header replaced, or removed when there are no entries. */
  private static final class RewrittenBaggageRequest extends HttpServletRequestWrapper {

    /** The new header value, or null when the header is removed. */
    private final @Nullable String baggage;

    /** Wraps the request with the new baggage entries. */
    private RewrittenBaggageRequest(HttpServletRequest request, List<String> entries) {
      super(request);
      this.baggage = entries.isEmpty() ? null : String.join(",", entries);
    }

    /**
     * Returns the header, with the new baggage in place of the caller's.
     *
     * @param name the header's name
     * @return its value, or null when the request does not have it
     */
    @Override
    public @Nullable String getHeader(String name) {
      return isBaggage(name) ? baggage : super.getHeader(name);
    }

    /**
     * Returns the header's values, with the new baggage in place of the caller's.
     *
     * @param name the header's name
     * @return its values, possibly none
     */
    @Override
    public Enumeration<String> getHeaders(String name) {
      return isBaggage(name)
          ? Collections.enumeration(Stream.ofNullable(baggage).toList())
          : super.getHeaders(name);
    }

    /**
     * Returns the names of the request's headers, with baggage only when there are entries.
     *
     * @return the names
     */
    @Override
    public Enumeration<String> getHeaderNames() {
      var otherNames =
          Collections.list(super.getHeaderNames()).stream().filter(name -> !isBaggage(name));
      var baggageName = Stream.ofNullable(baggage).map(_ -> WebConstants.BAGGAGE_HEADER);
      return Collections.enumeration(Stream.concat(otherNames, baggageName).toList());
    }

    /** Returns whether the header is the baggage header; header names ignore case. */
    private boolean isBaggage(String name) {
      return String.CASE_INSENSITIVE_ORDER.compare(WebConstants.BAGGAGE_HEADER, name) == 0;
    }
  }
}
