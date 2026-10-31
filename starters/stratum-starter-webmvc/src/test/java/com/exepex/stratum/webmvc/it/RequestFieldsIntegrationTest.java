package com.exepex.stratum.webmvc.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.test.StarterTestSupport;
import com.exepex.stratum.web.WebConstants;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;

@DisplayName("Every log line of a Spring MVC request carries its fields and correlation ID")
class RequestFieldsIntegrationTest {

  /** The address the test's requests come from. */
  private static final String LOOPBACK_ADDRESS = StarterTestSupport.ipAddress(127, 0, 0, 1);

  /** A client on the internet. */
  private static final String INTERNET_CLIENT_ADDRESS =
      StarterTestSupport.ipAddress(203, 0, 113, 7);

  /** A proxy inside the service's own network. */
  private static final String INNER_PROXY_ADDRESS = StarterTestSupport.ipAddress(10, 1, 2, 3);

  /** The path of the endpoint that returns the logging context its handler sees. */
  private static final String ORDER_FIELDS_PATH = "/orders/42/fields";

  /** A caller's correlation ID. */
  private static final String CALLER_ID = "checkout-777";

  /** Calls the service. */
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

  /**
   * Sends a GET request.
   *
   * @param port the service's port
   * @param pathAndQuery the path and query
   * @param headers header names and values
   * @return the response
   */
  static HttpResponse<String> get(int port, String pathAndQuery, String... headers)
      throws IOException, InterruptedException {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery)).GET();
    if (headers.length > 0) {
      request.headers(headers);
    }
    return HTTP_CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Nested
  @SpringBootTest(
      classes = RequestFieldsApplication.class,
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = {
        "stratum.web.enabled=true",
        "stratum.observability.enabled=true",
        "management.tracing.sampling.probability=1.0",
        "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:1/v1/traces",
        "management.otlp.metrics.export.enabled=false"
      })
  @DisplayName("with the default settings")
  class DefaultSettings {

    @LocalServerPort private int port;

    @Test
    @DisplayName("the handler's log lines carry method, route, path, query, client IP and agent")
    void handlerSeesRequestFields() throws Exception {
      var response =
          get(port, ORDER_FIELDS_PATH + "?expand=lines", HttpHeaders.USER_AGENT, "orders-cli/1.0");

      Map<String, String> fields = JsonPath.read(response.body(), "$");
      assertThat(fields)
          .containsEntry(LoggingContextConstants.HTTP_METHOD, "GET")
          .containsEntry(LoggingContextConstants.HTTP_ROUTE, "/orders/{orderId}/fields")
          .containsEntry(LoggingContextConstants.HTTP_PATH, ORDER_FIELDS_PATH)
          .containsEntry(LoggingContextConstants.HTTP_QUERY, "expand=lines")
          .containsEntry(LoggingContextConstants.CLIENT_IP, LOOPBACK_ADDRESS)
          .containsEntry(LoggingContextConstants.USER_AGENT, "orders-cli/1.0")
          .doesNotContainKeys(
              LoggingContextConstants.DEVICE_CLASS, LoggingContextConstants.CLIENT_COUNTRY);
    }

    @Test
    @DisplayName("work handed to Spring Boot's task executor carries the request fields")
    void asyncWorkSeesRequestFields() throws Exception {
      var response = get(port, "/orders/42/async-fields?expand=lines");

      Map<String, String> fields = JsonPath.read(response.body(), "$");
      assertThat(fields)
          .containsEntry(LoggingContextConstants.HTTP_ROUTE, "/orders/{orderId}/async-fields")
          .containsEntry(LoggingContextConstants.HTTP_QUERY, "expand=lines");
    }

    @Test
    @DisplayName("a request from a trusted proxy gets the client IP from X-Forwarded-For")
    void forwardedClientIp() throws Exception {
      var response =
          get(
              port,
              ORDER_FIELDS_PATH,
              "X-Forwarded-For",
              INTERNET_CLIENT_ADDRESS + ", " + INNER_PROXY_ADDRESS);

      assertThat(JsonPath.<String>read(response.body(), "$." + LoggingContextConstants.CLIENT_IP))
          .isEqualTo(INTERNET_CLIENT_ADDRESS);
    }

    @Test
    @DisplayName("the caller's X-Correlation-Id is logged, returned and sent to services called")
    void callerCorrelationId() throws Exception {
      var fields = get(port, ORDER_FIELDS_PATH, WebConstants.CORRELATION_ID_HEADER, CALLER_ID);
      var downstream = get(port, "/downstream", WebConstants.CORRELATION_ID_HEADER, CALLER_ID);

      assertThat(
              JsonPath.<String>read(fields.body(), "$." + LoggingContextConstants.CORRELATION_ID))
          .isEqualTo(CALLER_ID);
      assertThat(fields.headers().firstValue(WebConstants.CORRELATION_ID_HEADER))
          .hasValue(CALLER_ID);
      assertThat(downstream.body())
          .contains(LoggingContextConstants.CORRELATION_ID + "=" + CALLER_ID);
    }

    @Test
    @DisplayName("without a caller ID, the trace ID is returned and no baggage is added")
    void traceIdWhenNoCallerId() throws Exception {
      var fields = get(port, ORDER_FIELDS_PATH);
      var downstream = get(port, "/downstream");

      var traceId = JsonPath.<String>read(fields.body(), "$." + LoggingContextConstants.TRACE_ID);
      assertThat(fields.headers().firstValue(WebConstants.CORRELATION_ID_HEADER)).hasValue(traceId);
      assertThat(downstream.body()).doesNotContain(LoggingContextConstants.CORRELATION_ID);
    }

    @Test
    @DisplayName("an X-Correlation-Id with unsafe characters is ignored for the trace ID")
    void unsafeCallerIdIgnored() throws Exception {
      var fields = get(port, ORDER_FIELDS_PATH, WebConstants.CORRELATION_ID_HEADER, "a b");

      var traceId = JsonPath.<String>read(fields.body(), "$." + LoggingContextConstants.TRACE_ID);
      assertThat(fields.headers().firstValue(WebConstants.CORRELATION_ID_HEADER)).hasValue(traceId);
    }

    @Test
    @DisplayName("the request fields are gone from the server thread after the request")
    void fieldsDoNotLeakIntoNextRequest() throws Exception {
      get(port, ORDER_FIELDS_PATH + "?expand=lines");
      var next = get(port, "/orders/7/fields");

      assertThat(next.body()).doesNotContain("expand=lines");
    }
  }

  @Nested
  @SpringBootTest(
      classes = RequestFieldsApplication.class,
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = {
        "stratum.web.enabled=true",
        "stratum.observability.enabled=true",
        "management.tracing.sampling.probability=1.0",
        "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:1/v1/traces",
        "management.otlp.metrics.export.enabled=false",
        WebConstants.CORRELATION_ACCEPT_FROM_CALLER + "=false"
      })
  @DisplayName("with accept-from-caller=false")
  class CallerIdsIgnored {

    @LocalServerPort private int port;

    @Test
    @DisplayName("the caller's header and baggage are ignored and the trace ID is used")
    void callerIdsIgnored() throws Exception {
      var fields =
          get(
              port,
              ORDER_FIELDS_PATH,
              WebConstants.CORRELATION_ID_HEADER,
              CALLER_ID,
              "baggage",
              LoggingContextConstants.CORRELATION_ID + "=from-baggage");

      var traceId = JsonPath.<String>read(fields.body(), "$." + LoggingContextConstants.TRACE_ID);
      assertThat(
              JsonPath.<String>read(fields.body(), "$." + LoggingContextConstants.CORRELATION_ID))
          .isEqualTo(traceId);
      assertThat(fields.headers().firstValue(WebConstants.CORRELATION_ID_HEADER)).hasValue(traceId);
    }
  }
}
