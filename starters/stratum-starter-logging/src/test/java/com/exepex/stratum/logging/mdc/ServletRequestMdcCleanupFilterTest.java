package com.exepex.stratum.logging.mdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("ServletRequestMdcCleanupFilter leaves the MDC as each request found it")
class ServletRequestMdcCleanupFilterTest {

  /** A field the service puts while it handles a request. */
  private static final String CUSTOMER_ID = "customerId";

  /** A field the thread held before the request. */
  private static final String TENANT_ID = "tenantId";

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
        (request, response) -> MDC.put(CUSTOMER_ID, "CUST-A"));
    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();

    MDC.put(TENANT_ID, "TENANT-3");
    filter.doFilter(
        new MockHttpServletRequest(),
        new MockHttpServletResponse(),
        (request, response) -> {
          MDC.remove(TENANT_ID);
          MDC.put(CUSTOMER_ID, "CUST-B");
        });
    assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of(TENANT_ID, "TENANT-3"));
  }

  @Test
  @DisplayName("the MDC is restored when the request fails too")
  void restoresTheMdcWhenTheRequestFails() {
    assertThatThrownBy(
            () ->
                filter.doFilter(
                    new MockHttpServletRequest(),
                    new MockHttpServletResponse(),
                    (request, response) -> {
                      MDC.put(CUSTOMER_ID, "CUST-A");
                      throw new ServletException("handler failed");
                    }))
        .isInstanceOf(ServletException.class);

    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
  }
}
