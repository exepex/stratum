package com.exepex.stratum.webflux.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.test.LogLineCapture;
import com.exepex.stratum.test.ServerRequestTelemetry;
import com.exepex.stratum.test.ServerRequestTelemetry.Recorded;
import com.exepex.stratum.test.WebTestSupport;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

@SpringBootTest(
    classes = SlowApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "stratum.backward-compatible=false",
      WebConstants.TIMEOUT_DEFAULT_LIMIT + "=30s",
      WebConstants.TIMEOUT_ROUTES + "[/slow/**]=1s",
      WebConstants.TIMEOUT_STATUS + "=504",
      "management.tracing.sampling.probability=1.0",
      "management.tracing.export.otlp.enabled=false",
      "management.otlp.metrics.export.enabled=false"
    })
@Import(ServerRequestTelemetry.class)
// A request that waited for the 30 second default instead of its own limit fails the test.
@Timeout(15)
@DisplayName("A reactive request that runs out of time is cancelled and answered with the status")
class ResponseTimeoutIntegrationTest {

  /** The request log lines. */
  private final LogLineCapture lines = new LogLineCapture(WebConstants.REQUEST_LOG_LOGGER);

  @LocalServerPort private int port;

  @Autowired private ServerRequestTelemetry telemetry;

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
      "a request waiting past its route's limit gets SX-WEB-1007, and its log line the status")
  void routeLimit() throws Exception {
    var response = WebTestSupport.getFromService(port, "/slow/blocked");

    WebTestSupport.checkProblemDetails(
        response, HttpStatus.GATEWAY_TIMEOUT.value(), WebErrorCode.REQUEST_TIMED_OUT.getCode());
    assertThat(lines.fieldsOf(lines.onlyLine()))
        .containsEntry(WebConstants.HTTP_STATUS, HttpStatus.GATEWAY_TIMEOUT.value());
  }

  @Test
  @DisplayName("a method's @ResponseTimeout gives it more time than its route")
  void annotationWinsOverRoute() throws Exception {
    var response = WebTestSupport.getFromService(port, "/slow/given-more-time");

    assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
    assertThat(response.body()).isEqualTo("done");
  }

  @Test
  @DisplayName("a method's @ResponseTimeout gives it less time than the default")
  void annotationWinsOverDefault() throws Exception {
    var response = WebTestSupport.getFromService(port, "/orders/own-limit");

    WebTestSupport.checkProblemDetails(
        response, HttpStatus.GATEWAY_TIMEOUT.value(), WebErrorCode.REQUEST_TIMED_OUT.getCode());
  }

  @Test
  @DisplayName("a stream that started in time is not cut off")
  void startedStream() throws Exception {
    var response = WebTestSupport.getFromService(port, "/slow/lines");

    assertThat(response.body()).isEqualTo("line 0\nline 1\nline 2\nline 3\n");
  }

  @Test
  @DisplayName("the service's own timeout is answered as the failure it is, not as a time limit")
  void serviceTimeoutIsKept() throws Exception {
    var response = WebTestSupport.getFromService(port, "/orders/own-timeout");

    WebTestSupport.checkProblemDetails(
        response, HttpStatus.INTERNAL_SERVER_ERROR.value(), WebErrorCode.INTERNAL_ERROR.getCode());
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"/slow/blocked"})
  @DisplayName(
      "a request that runs out of time records RequestTimedOutException, as on both stacks")
  void recordsTimeout(String path) throws Exception {
    WebTestSupport.getFromService(port, path);

    telemetry.check(new Recorded(path, "RequestTimedOutException", Recorded.NONE, true));
  }
}
