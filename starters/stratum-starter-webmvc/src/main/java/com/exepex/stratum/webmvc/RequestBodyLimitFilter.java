package com.exepex.stratum.webmvc;

import com.exepex.stratum.web.RequestBodyLimits;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Counts each request's body as it is read, and fails the request with {@code 413} once the body is
 * larger than the limit of its route or the default. {@link RequestBodyLimitInterceptor} gives it
 * the handler's own limit and checks the declared length before the handler reads the body.
 */
@RequiredArgsConstructor
public class RequestBodyLimitFilter extends OncePerRequestFilter {

  /** Runs just inside the response timeout filter, before anything reads the body. */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 7;

  /** The request attribute that holds the request's counter, for its handler's own limit. */
  static final String COUNTER_ATTRIBUTE = RequestBodyLimitFilter.class.getName() + ".counter";

  /** Decides each request's limit. */
  private final RequestBodyLimits requestBodyLimits;

  /**
   * Runs the rest of the chain with the request's body counted.
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
    var counter =
        requestBodyLimits.start(
            request.getRequestURI().substring(request.getContextPath().length()));
    request.setAttribute(COUNTER_ATTRIBUTE, counter);
    try {
      filterChain.doFilter(new BodyCountingRequest(request, counter), response);
    } finally {
      // The error page dispatch that answers a body that was too large must not be checked again.
      request.removeAttribute(COUNTER_ATTRIBUTE);
    }
  }
}
