package com.exepex.stratum.resilience.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.resilience.ResilienceConstants;
import com.exepex.stratum.resilience.testsupport.HeldRequests;
import com.exepex.stratum.resilience.testsupport.RouteRequests;
import com.exepex.stratum.test.WebTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

@SpringBootTest(
    classes = LimitedApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "server.servlet.context-path=/api",
      ResilienceConstants.INCOMING_ROUTES + "[/reports/**].max-concurrent=1",
      ResilienceConstants.INCOMING_ROUTES + "[/fn/**].max-concurrent=1"
    })
@DisplayName("Routes are paths within the application, behind its context path")
class ContextPathIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private HeldRequests held;

  @Test
  @DisplayName("/api/reports/1 is limited, /reports/1 is outside the application")
  void routeBehindContextPath() throws Exception {
    var statuses =
        RouteRequests.whileFull(
            port,
            "/api/reports/held",
            held,
            () ->
                new int[] {
                  WebTestSupport.getFromService(port, "/api/reports/1").statusCode(),
                  WebTestSupport.getFromService(port, "/reports/1").statusCode()
                });

    assertThat(statuses)
        .containsExactly(HttpStatus.SERVICE_UNAVAILABLE.value(), HttpStatus.NOT_FOUND.value());
  }

  @Test
  @DisplayName("a functional endpoint is limited by its path like any handler")
  void functionalEndpointLimited() throws Exception {
    var status =
        RouteRequests.whileFull(
            port,
            "/api/fn/held",
            held,
            () -> WebTestSupport.getFromService(port, "/api/fn/held").statusCode());

    assertThat(status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
  }
}
