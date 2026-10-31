package com.exepex.stratum.webmvc.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.test.LogLineCapture;
import com.exepex.stratum.test.StarterTestSupport;
import com.exepex.stratum.web.WebConstants;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

@SpringBootTest(
    classes = RequestFieldsApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "stratum.web.enabled=true",
      WebConstants.REQUEST_LOG_REQUEST_HEADERS + "[X-Tenant-Id]=tenantId",
      WebConstants.REQUEST_LOG_TOKEN_PAYLOAD + "=true"
    })
@DisplayName("Each Spring MVC request writes one log line once it has been answered")
class RequestLogIntegrationTest {

  /** The path of an endpoint that answers at once. */
  private static final String ORDER_FIELDS_PATH = "/orders/42/fields";

  /** A token payload, as a caller's identity provider writes it. */
  private static final String PAYLOAD = "{\"sub\":\"checkout-service\"}";

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
  @DisplayName("the line has the status, route, request fields, named header and token payload")
  void answeredRequest() throws Exception {
    var token =
        "Bearer eyJhbGciOiJSUzI1NiJ9."
            + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(PAYLOAD.getBytes(StandardCharsets.UTF_8))
            + ".c2lnbmF0dXJl";

    StarterTestSupport.getFromService(
        port, ORDER_FIELDS_PATH, "X-Tenant-Id", "acme", HttpHeaders.AUTHORIZATION, token);

    var line = lines.onlyLine();
    assertThat(line.getFormattedMessage())
        .startsWith("GET /orders/{orderId}/fields answered 200 in ");
    assertThat(line.getMDCPropertyMap())
        .containsEntry(LoggingContextConstants.HTTP_PATH, ORDER_FIELDS_PATH);
    assertThat(lines.fieldsOf(line))
        .containsEntry(WebConstants.HTTP_STATUS, HttpStatus.OK.value())
        .containsEntry("tenantId", "acme")
        .containsEntry(WebConstants.TOKEN_PAYLOAD, PAYLOAD);
  }

  @Test
  @DisplayName("a path with no handler is logged with its 404")
  void unknownPath() throws Exception {
    StarterTestSupport.getFromService(port, "/no-such-path");

    assertThat(lines.fieldsOf(lines.onlyLine()))
        .containsEntry(WebConstants.HTTP_STATUS, HttpStatus.NOT_FOUND.value());
  }

  @Test
  @DisplayName("a request answered on an asynchronous dispatch writes one line, when answered")
  void asyncRequest() throws Exception {
    StarterTestSupport.getFromService(port, "/orders/42/response-thread-fields");

    assertThat(lines.onlyLine().getFormattedMessage())
        .startsWith("GET /orders/{orderId}/response-thread-fields answered 200 in ");
  }

  @Test
  @DisplayName("the actuator's paths and static files write no line")
  void skippedRequests() throws Exception {
    StarterTestSupport.getFromService(port, "/actuator/health");
    StarterTestSupport.getFromService(port, "/app.js");
    StarterTestSupport.getFromService(port, ORDER_FIELDS_PATH);

    assertThat(lines.onlyLine().getMDCPropertyMap())
        .containsEntry(LoggingContextConstants.HTTP_PATH, ORDER_FIELDS_PATH);
  }
}
