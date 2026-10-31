package com.exepex.stratum.webmvc.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.test.StarterTestSupport;
import com.exepex.stratum.web.WebConstants;
import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

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

  /** The query string of the test requests. */
  private static final String EXPAND_QUERY = "expand=lines";

  /** The user agent the request sends. */
  private static final String USER_AGENT = "orders-cli/1.0";

  /** The number of hex digits in a trace ID. */
  private static final int TRACE_ID_LENGTH = 32;

  /** A caller's correlation ID. */
  private static final String CALLER_ID = "checkout-777";

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
          StarterTestSupport.getFromService(
              port, ORDER_FIELDS_PATH + "?" + EXPAND_QUERY, HttpHeaders.USER_AGENT, USER_AGENT);

      StarterTestSupport.checkRequestFields(
          response, "/orders/{orderId}/fields", ORDER_FIELDS_PATH + "?" + EXPAND_QUERY, USER_AGENT);
    }

    @Test
    @DisplayName("work handed to Spring Boot's task executor carries the request fields")
    void asyncWorkSeesRequestFields() throws Exception {
      var response =
          StarterTestSupport.getFromService(port, "/orders/42/async-fields?" + EXPAND_QUERY);

      Map<String, String> fields = JsonPath.read(response.body(), "$");
      assertThat(fields)
          .containsEntry(LoggingContextConstants.HTTP_ROUTE, "/orders/{orderId}/async-fields")
          .containsEntry(LoggingContextConstants.HTTP_QUERY, EXPAND_QUERY);
    }

    @Test
    @DisplayName(
        "a response written on an asynchronous dispatch carries the request fields and the"
            + " caller's correlation ID")
    void asyncDispatchSeesRequestFields() throws Exception {
      var response =
          StarterTestSupport.getFromService(
              port,
              "/orders/42/response-thread-fields?" + EXPAND_QUERY,
              WebConstants.CORRELATION_ID_HEADER,
              CALLER_ID);

      Map<String, String> fields = JsonPath.read(response.body(), "$");
      assertThat(fields)
          .containsEntry(LoggingContextConstants.HTTP_PATH, "/orders/42/response-thread-fields")
          .containsEntry(LoggingContextConstants.HTTP_QUERY, EXPAND_QUERY)
          .containsEntry(LoggingContextConstants.CLIENT_IP, LOOPBACK_ADDRESS)
          .containsEntry(LoggingContextConstants.CORRELATION_ID, CALLER_ID);
    }

    @Test
    @DisplayName("a request from a trusted proxy gets the client IP from X-Forwarded-For")
    void forwardedClientIp() throws Exception {
      var response =
          StarterTestSupport.getFromService(
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
      var fields =
          StarterTestSupport.getFromService(
              port, ORDER_FIELDS_PATH, WebConstants.CORRELATION_ID_HEADER, CALLER_ID);
      var downstream =
          StarterTestSupport.getFromService(
              port, "/downstream", WebConstants.CORRELATION_ID_HEADER, CALLER_ID);

      assertThat(
              JsonPath.<String>read(fields.body(), "$." + LoggingContextConstants.CORRELATION_ID))
          .isEqualTo(CALLER_ID);
      assertThat(fields.headers().firstValue(WebConstants.CORRELATION_ID_HEADER))
          .hasValue(CALLER_ID);
      assertThat(downstream.body())
          .contains(LoggingContextConstants.CORRELATION_ID + "=" + CALLER_ID);
    }

    @Test
    @DisplayName("the error body of an unknown path keeps its trace ID and the caller's ID")
    void callerCorrelationIdInErrorBody() throws Exception {
      var response =
          StarterTestSupport.getFromService(
              port, "/no-such-path", WebConstants.CORRELATION_ID_HEADER, CALLER_ID);

      assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
      assertThat(
              JsonPath.<String>read(response.body(), "$." + LoggingContextConstants.CORRELATION_ID))
          .isEqualTo(CALLER_ID);
      assertThat(JsonPath.<String>read(response.body(), "$." + LoggingContextConstants.TRACE_ID))
          .matches("\\p{XDigit}{32}")
          .isNotEqualTo("0".repeat(TRACE_ID_LENGTH));
      assertThat(response.headers().firstValue(WebConstants.CORRELATION_ID_HEADER))
          .hasValue(CALLER_ID);
    }

    @Test
    @DisplayName("without a caller ID, the trace ID is returned and no baggage is added")
    void traceIdWhenNoCallerId() throws Exception {
      var fields = StarterTestSupport.getFromService(port, ORDER_FIELDS_PATH);
      var downstream = StarterTestSupport.getFromService(port, "/downstream");

      var traceId = JsonPath.<String>read(fields.body(), "$." + LoggingContextConstants.TRACE_ID);
      assertThat(fields.headers().firstValue(WebConstants.CORRELATION_ID_HEADER)).hasValue(traceId);
      assertThat(downstream.body()).doesNotContain(LoggingContextConstants.CORRELATION_ID);
    }

    @Test
    @DisplayName("an X-Correlation-Id with unsafe characters is ignored for the trace ID")
    void unsafeCallerIdIgnored() throws Exception {
      var fields =
          StarterTestSupport.getFromService(
              port, ORDER_FIELDS_PATH, WebConstants.CORRELATION_ID_HEADER, "a b");

      var traceId = JsonPath.<String>read(fields.body(), "$." + LoggingContextConstants.TRACE_ID);
      assertThat(fields.headers().firstValue(WebConstants.CORRELATION_ID_HEADER)).hasValue(traceId);
    }

    @Test
    @DisplayName("the request fields are gone from the server thread after the request")
    void fieldsDoNotLeakIntoNextRequest() throws Exception {
      StarterTestSupport.getFromService(port, ORDER_FIELDS_PATH + "?" + EXPAND_QUERY);
      var next = StarterTestSupport.getFromService(port, "/orders/7/fields");

      assertThat(next.body()).doesNotContain(EXPAND_QUERY);
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
          StarterTestSupport.getFromService(
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
