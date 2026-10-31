package com.exepex.stratum.webmvc;

import com.exepex.stratum.core.error.LoggingContextConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps the request's trace and correlation IDs on the request while tracing's scope is open. The
 * error page runs in a second dispatch after that scope has closed, so it reads them from here,
 * whatever failed: a controller, a later filter or a {@code sendError} call.
 */
public class RequestIdsFilter extends OncePerRequestFilter {

  /** Runs just inside the correlation ID filter, so the error page gets the ID it chose. */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 3;

  /** The request attribute that keeps the request's trace ID. */
  static final String TRACE_ID_ATTRIBUTE =
      RequestIdsFilter.class.getName() + "." + LoggingContextConstants.TRACE_ID;

  /** The request attribute that keeps the request's correlation ID. */
  static final String CORRELATION_ID_ATTRIBUTE =
      RequestIdsFilter.class.getName() + "." + LoggingContextConstants.CORRELATION_ID;

  /**
   * Keeps the IDs from the logging context on the request, then runs the rest of the chain.
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
    request.setAttribute(TRACE_ID_ATTRIBUTE, MDC.get(LoggingContextConstants.TRACE_ID));
    request.setAttribute(CORRELATION_ID_ATTRIBUTE, MDC.get(LoggingContextConstants.CORRELATION_ID));
    filterChain.doFilter(request, response);
  }
}
