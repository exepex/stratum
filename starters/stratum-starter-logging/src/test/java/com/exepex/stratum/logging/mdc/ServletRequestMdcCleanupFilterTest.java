package com.exepex.stratum.logging.mdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequestEvent;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;

@DisplayName("The servlet MDC cleanup leaves the MDC as each request found it")
class ServletRequestMdcCleanupFilterTest {

  /** A field the service puts while it handles a request. */
  private static final String CUSTOMER_ID = "customerId";

  /** A field the thread held before the request. */
  private static final String TENANT_ID = "tenantId";

  /** The tenant the thread held before the request. */
  private static final String TENANT = "TENANT-3";

  /** The customer whose request leaves its field behind. */
  private static final String CUSTOMER = "CUST-A";

  private final ServletRequestMdcCleanupFilter filter = new ServletRequestMdcCleanupFilter();

  /** Clears the logging context, so no test sees fields left by another. */
  @AfterEach
  void clearLoggingContext() {
    MDC.clear();
  }

  @Test
  @DisplayName(
      "a field the request put and never removed is gone when it ends, and what the thread held"
          + " before is back")
  void restoresTheMdcTheThreadHeldBefore() throws Exception {
    filter.doFilter(
        new MockHttpServletRequest(),
        new MockHttpServletResponse(),
        (request, response) -> MDC.put(CUSTOMER_ID, CUSTOMER));
    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();

    MDC.put(TENANT_ID, TENANT);
    filter.doFilter(
        new MockHttpServletRequest(),
        new MockHttpServletResponse(),
        (request, response) -> {
          MDC.remove(TENANT_ID);
          MDC.put(CUSTOMER_ID, "CUST-B");
        });
    assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of(TENANT_ID, TENANT));
  }

  @Test
  @DisplayName(
      "when the request fails, its fields stay for the server's failure line, and the listener"
          + " restores the MDC when the request ends")
  void keepsFieldsOfFailedRequestUntilItEnds() {
    var request = new MockHttpServletRequest();
    assertThatThrownBy(
            () ->
                filter.doFilter(
                    request,
                    new MockHttpServletResponse(),
                    (failingRequest, response) -> {
                      MDC.put(CUSTOMER_ID, CUSTOMER);
                      throw new ServletException("handler failed");
                    }))
        .isInstanceOf(ServletException.class);
    assertThat(MDC.get(CUSTOMER_ID)).isEqualTo(CUSTOMER);

    new ServletRequestMdcCleanupListener()
        .requestDestroyed(new ServletRequestEvent(new MockServletContext(), request));

    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
  }

  @Test
  @DisplayName(
      "after a failure, the error page's dispatch restores the MDC from before the failure when it"
          + " ends, on the failed request's thread")
  void errorDispatchRestoresTheMdcFromBeforeTheFailure() throws Exception {
    var request = new MockHttpServletRequest();
    assertThatThrownBy(
            () ->
                filter.doFilter(
                    request,
                    new MockHttpServletResponse(),
                    (failingRequest, response) -> {
                      MDC.put(CUSTOMER_ID, CUSTOMER);
                      throw new ServletException("handler failed");
                    }))
        .isInstanceOf(ServletException.class);

    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (errorRequest, response) -> MDC.put(TENANT_ID, TENANT));

    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    assertThat(request.getAttribute(ServletRequestMdcCleanupFilter.RESTORE_MDC_ATTRIBUTE)).isNull();
  }

  @Test
  @DisplayName("the listener leaves the MDC alone after a request that did not fail")
  void listenerIgnoresRequestsThatDidNotFail() {
    MDC.put(TENANT_ID, TENANT);

    new ServletRequestMdcCleanupListener()
        .requestDestroyed(
            new ServletRequestEvent(new MockServletContext(), new MockHttpServletRequest()));

    assertThat(MDC.get(TENANT_ID)).isEqualTo(TENANT);
  }
}
