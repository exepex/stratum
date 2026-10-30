package com.exepex.stratum.logging.mdc;

import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;

/**
 * Puts the MDC back as it was before a failed request once the server has finished with it, so the
 * server's own log line for the failure still carries the request's fields and the next request on
 * the thread does not.
 */
public class ServletRequestMdcCleanupListener implements ServletRequestListener {

  /**
   * Restores the MDC when {@link ServletRequestMdcCleanupFilter} left the request's fields for the
   * server's failure line.
   *
   * @param event the request that ended
   */
  @Override
  public void requestDestroyed(ServletRequestEvent event) {
    if (event.getServletRequest().getAttribute(ServletRequestMdcCleanupFilter.RESTORE_MDC_ATTRIBUTE)
        instanceof Runnable restoreMdc) {
      restoreMdc.run();
    }
  }
}
