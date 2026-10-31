package com.exepex.stratum.webflux.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.test.StarterTestSupport;
import com.exepex.stratum.web.WebConstants;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
@DisplayName("Each reactive request writes one log line once its response starts")
class RequestLogIntegrationTest {

  /** The path of an endpoint that answers at once. */
  private static final String ORDER_FIELDS_PATH = "/orders/42/fields";

  /** A token payload, as a caller's identity provider writes it. */
  private static final String PAYLOAD = "{\"sub\":\"checkout-service\"}";

  /**
   * The request log lines, each with the logging context it was written in. The context is copied
   * when the line is written, because the request clears it straight after.
   */
  private final ListAppender<ILoggingEvent> lines =
      new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent line) {
          line.prepareForDeferredProcessing();
          super.append(line);
        }
      };

  @LocalServerPort private int port;

  /** Returns the only request log line, once it has been written. */
  private ILoggingEvent onlyRequestLogLine() {
    await().untilAsserted(() -> assertThat(lines.list).isNotEmpty());
    assertThat(lines.list).hasSize(1);
    return lines.list.getFirst();
  }

  /** Returns the key-value pairs of a line. */
  private Map<String, Object> fieldsOf(ILoggingEvent line) {
    return line.getKeyValuePairs().stream()
        .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
  }

  /** Captures the request log lines. */
  @BeforeEach
  void captureLines() {
    lines.start();
    ((Logger) LoggerFactory.getLogger(WebConstants.REQUEST_LOG_LOGGER)).addAppender(lines);
  }

  /** Stops capturing. */
  @AfterEach
  void stopCapturing() {
    ((Logger) LoggerFactory.getLogger(WebConstants.REQUEST_LOG_LOGGER)).detachAppender(lines);
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

    var line = onlyRequestLogLine();
    assertThat(line.getFormattedMessage())
        .startsWith("GET /orders/{orderId}/fields answered 200 in ");
    assertThat(line.getMDCPropertyMap())
        .containsEntry(LoggingContextConstants.HTTP_PATH, ORDER_FIELDS_PATH);
    assertThat(fieldsOf(line))
        .containsEntry(WebConstants.HTTP_STATUS, HttpStatus.OK.value())
        .containsEntry("tenantId", "acme")
        .containsEntry(WebConstants.TOKEN_PAYLOAD, PAYLOAD);
  }

  @Test
  @DisplayName("a path with no handler is logged with its 404")
  void unknownPath() throws Exception {
    StarterTestSupport.getFromService(port, "/no-such-path");

    assertThat(fieldsOf(onlyRequestLogLine()))
        .containsEntry(WebConstants.HTTP_STATUS, HttpStatus.NOT_FOUND.value());
  }

  @Test
  @DisplayName("the actuator's paths and static files write no line")
  void skippedRequests() throws Exception {
    StarterTestSupport.getFromService(port, "/actuator/health");
    StarterTestSupport.getFromService(port, "/app.js");
    StarterTestSupport.getFromService(port, ORDER_FIELDS_PATH);

    assertThat(onlyRequestLogLine().getMDCPropertyMap())
        .containsEntry(LoggingContextConstants.HTTP_PATH, ORDER_FIELDS_PATH);
  }
}
