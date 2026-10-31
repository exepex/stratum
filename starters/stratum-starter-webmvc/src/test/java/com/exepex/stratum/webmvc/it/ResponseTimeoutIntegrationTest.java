package com.exepex.stratum.webmvc.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.test.LogLineCapture;
import com.exepex.stratum.test.StarterTestSupport;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

@SpringBootTest(
    classes = SlowApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "stratum.web.enabled=true",
      WebConstants.TIMEOUT_DEFAULT_LIMIT + "=30s",
      WebConstants.TIMEOUT_ROUTES + "[/slow/**]=1s",
      WebConstants.TIMEOUT_STATUS + "=504",
      "stratum.observability.enabled=true",
      "management.tracing.sampling.probability=1.0",
      "management.tracing.export.enabled=false",
      "management.otlp.metrics.export.enabled=false"
    })
// A request that waited for the 30 second default instead of its own limit fails the test.
@Timeout(15)
@DisplayName(
    "A Spring MVC request that runs out of time is interrupted and answered with the status")
class ResponseTimeoutIntegrationTest {

  /** The request log lines. */
  private final LogLineCapture lines = new LogLineCapture(WebConstants.REQUEST_LOG_LOGGER);

  @LocalServerPort private int port;

  /** Captures the request log lines. */
  @BeforeEach
  void captureLines() {
    lines.start();
  }

  /** Stops capturing. */
  @AfterEach
  void stopCapturing() {
    lines.stop();
  }

  @Test
  @DisplayName(
      "a request blocked past its route's limit gets SX-WEB-1007, and its log line the status")
  void routeLimit() throws Exception {
    var response = StarterTestSupport.getFromService(port, "/slow/blocked");

    StarterTestSupport.checkProblemDetails(
        response, HttpStatus.GATEWAY_TIMEOUT.value(), WebErrorCode.REQUEST_TIMED_OUT.getCode());
    assertThat(lines.fieldsOf(lines.onlyLine()))
        .containsEntry(WebConstants.HTTP_STATUS, HttpStatus.GATEWAY_TIMEOUT.value());
  }

  @Test
  @DisplayName("a method's @ResponseTimeout gives it more time than its route")
  void annotationWinsOverRoute() throws Exception {
    var response = StarterTestSupport.getFromService(port, "/slow/given-more-time");

    assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
    assertThat(response.body()).isEqualTo("done");
  }

  @Test
  @DisplayName("a method's @ResponseTimeout gives it less time than the default")
  void annotationWinsOverDefault() throws Exception {
    var response = StarterTestSupport.getFromService(port, "/orders/own-limit");

    StarterTestSupport.checkProblemDetails(
        response, HttpStatus.GATEWAY_TIMEOUT.value(), WebErrorCode.REQUEST_TIMED_OUT.getCode());
  }
}
