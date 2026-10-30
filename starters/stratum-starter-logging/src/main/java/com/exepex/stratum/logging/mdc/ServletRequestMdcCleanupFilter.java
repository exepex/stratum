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
   * request that failed. The error page's dispatch runs it when it ends, or {@link
   * ServletRequestMdcCleanupListener} when the request ends without one.
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
      if (!dispatchCompleted) {
        keepFieldsUntilFailureIsLogged(request, mdcBeforeDispatch);
      } else if (request.getAttribute(RESTORE_MDC_ATTRIBUTE) instanceof Runnable restoreMdc) {
        // The error page ran after the server logged the failure, so restore the MDC from before
        // the failure now, on this thread, before the thread can go back to the pool.
        request.removeAttribute(RESTORE_MDC_ATTRIBUTE);
        restoreMdc.run();
      } else {
        MDC.setContextMap(mdcBeforeDispatch);
      }
    }
  }

  /**
   * Leaves the failed request's fields on the thread and stores how to restore the MDC, unless an
   * earlier failure of the same request already did. The restore only acts on this thread.
   */
  private void keepFieldsUntilFailureIsLogged(
      ServletRequest request, Map<String, String> mdcBeforeDispatch) {
    if (request.getAttribute(RESTORE_MDC_ATTRIBUTE) == null) {
      var failedThreadId = Thread.currentThread().threadId();
      request.setAttribute(
          RESTORE_MDC_ATTRIBUTE,
          (Runnable)
              () -> {
                if (Thread.currentThread().threadId() == failedThreadId) {
                  MDC.setContextMap(mdcBeforeDispatch);
                }
              });
    }
  }
}
