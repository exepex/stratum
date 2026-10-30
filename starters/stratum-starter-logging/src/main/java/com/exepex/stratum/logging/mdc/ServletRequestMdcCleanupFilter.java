package com.exepex.stratum.logging.mdc;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import org.slf4j.MDC;

/**
 * Puts the MDC back as it was when each servlet request ends. A field the service put and never
 * removed, such as {@code customerId}, would otherwise stay on the server thread and appear in the
 * next request that thread serves, and in the tasks that request hands to other threads.
 */
public class ServletRequestMdcCleanupFilter implements Filter {

  /**
   * Runs the request, then restores the MDC the thread held before it.
   *
   * @param request the request
   * @param response the response
   * @param chain the rest of the request's filters and its handler
   * @throws IOException when the rest of the chain throws it
   * @throws ServletException when the rest of the chain throws it
   */
  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    var mdcBeforeRequest = MDC.getCopyOfContextMap();
    try {
      chain.doFilter(request, response);
    } finally {
      if (mdcBeforeRequest == null) {
        MDC.clear();
      } else {
        MDC.setContextMap(mdcBeforeRequest);
      }
    }
  }
}
