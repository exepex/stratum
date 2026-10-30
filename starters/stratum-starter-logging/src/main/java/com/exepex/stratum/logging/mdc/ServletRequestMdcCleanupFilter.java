package com.exepex.stratum.logging.mdc;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import org.slf4j.MDC;

/**
 * Puts the MDC back as it was when each servlet request ends. A field the service put and never
 * removed, such as {@code customerId}, would otherwise stay on the server thread and appear in the
 * next request that thread serves, and in the tasks that request hands to other threads.
 */
public class ServletRequestMdcCleanupFilter implements Filter {

  /**
   * The request attribute that holds a {@link Runnable} putting the MDC back as it was before a
   * request that failed, run by {@link ServletRequestMdcCleanupListener} when the request ends.
   */
  public static final String RESTORE_MDC_ATTRIBUTE =
      ServletRequestMdcCleanupFilter.class.getName() + ".RESTORE_MDC";

  /**
   * Runs the request, then restores the MDC the thread held before it. When the request fails, the
   * fields stay until the request ends, because the server logs the failure after this filter.
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
    var mdcBeforeDispatch =
        Objects.requireNonNullElse(MDC.getCopyOfContextMap(), Map.<String, String>of());
    var dispatchCompleted = false;
    try {
      chain.doFilter(request, response);
      dispatchCompleted = true;
    } finally {
      if (dispatchCompleted) {
        MDC.setContextMap(mdcBeforeDispatch);
      } else if (request.getAttribute(RESTORE_MDC_ATTRIBUTE) == null) {
        // Only the first failure's state is kept: a later failure, such as one in the error page,
        // starts from the fields this one left.
        request.setAttribute(
            RESTORE_MDC_ATTRIBUTE, (Runnable) () -> MDC.setContextMap(mdcBeforeDispatch));
      }
    }
  }
}
