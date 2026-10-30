package com.exepex.stratum.webmvc.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebErrorCode;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

@DisplayName("A Spring MVC service with the web module on answers every error the same way")
class ErrorResponsesIntegrationTest {

  /** The member of the error body that holds the trace ID. */
  private static final String TRACE_ID_PATH = "$." + LoggingContextConstants.TRACE_ID;

  /** The member of the error body that holds the code. */
  private static final String CODE_PATH = "$." + WebConstants.CODE;

  /** The running service. */
  private static ConfigurableApplicationContext service;

  /** Calls the service. */
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

  /** Starts the service on a free port, with tracing on and nothing exported. */
  @BeforeAll
  static void startService() {
    service =
        new SpringApplicationBuilder(OrdersApplication.class)
            .run(
                "--server.port=0",
                "--" + WebConstants.ENABLED + "=true",
                "--" + WebConstants.ERROR_STATUSES + ".ORD-0001=409",
                "--stratum.observability.enabled=true",
                "--management.tracing.sampling.probability=1.0",
                "--management.tracing.export.enabled=false",
                "--management.otlp.metrics.export.enabled=false");
  }

  /** Stops the service. */
  @AfterAll
  static void stopService() {
    service.close();
  }

  @Test
  @DisplayName(
      "a mapped StratumException gets its status, code and trace ID, and no stack trace or path")
  void mappedStratumException() throws Exception {
    var path = "/orders/duplicate";
    var response = get(path, MediaType.APPLICATION_JSON);

    assertProblem(response, HttpStatus.CONFLICT, "ORD-0001");
    assertThat(JsonPath.<String>read(response.body(), "$.detail"))
        .isEqualTo("Order already exists: order 42");
    assertThat(JsonPath.<String>read(response.body(), "$.severity")).isEqualTo("ERROR");
    assertThat(
            JsonPath.<String>read(response.body(), "$." + LoggingContextConstants.CORRELATION_ID))
        .isNotBlank();
    assertThat(response.body()).doesNotContain(path);
  }

  @Test
  @DisplayName("an unmapped StratumException gets 500 with its own code")
  void unmappedStratumException() throws Exception {
    assertProblem(
        get("/orders/unpriced", MediaType.APPLICATION_JSON),
        HttpStatus.INTERNAL_SERVER_ERROR,
        "ORD-0003");
  }

  @Test
  @DisplayName("an invalid body gets SX-WEB-1001 with each field, never quoting the value")
  void invalidBody() throws Exception {
    var response =
        send(
            HttpRequest.newBuilder(uri("/orders"))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"quantity\":0,\"account\":\"" + OrdersApplication.IBAN + "\"}")));

    assertProblem(response, HttpStatus.BAD_REQUEST, WebErrorCode.INVALID_FIELDS.getCode());
    assertThat(JsonPath.<Object>read(response.body(), "$.details[*].field").toString())
        .contains("quantity", "account");
    assertThat(response.body()).doesNotContain(OrdersApplication.IBAN);
  }

  @Test
  @DisplayName("an invalid parameter gets SX-WEB-1001 naming the parameter")
  void invalidParameter() throws Exception {
    var response = get("/orders/search?quantity=0", MediaType.APPLICATION_JSON);

    assertProblem(response, HttpStatus.BAD_REQUEST, WebErrorCode.INVALID_FIELDS.getCode());
    assertThat(JsonPath.<Object>read(response.body(), "$.details[0].field")).isEqualTo("quantity");
  }

  @Test
  @DisplayName("a path variable of the wrong type gets SX-WEB-1002")
  void unreadableRequest() throws Exception {
    assertProblem(
        get("/orders/abc", MediaType.APPLICATION_JSON),
        HttpStatus.BAD_REQUEST,
        WebErrorCode.UNREADABLE_REQUEST.getCode());
  }

  @Test
  @DisplayName("an unknown path gets SX-WEB-1003 with the trace ID")
  void unknownPath() throws Exception {
    assertProblem(
        get("/no-such-path", MediaType.APPLICATION_JSON),
        HttpStatus.NOT_FOUND,
        WebErrorCode.NOT_FOUND.getCode());
  }

  @Test
  @DisplayName("an exception with @ResponseStatus gets its status and SX-WEB-1004")
  void responseStatusException() throws Exception {
    assertProblem(
        get("/orders/gone", MediaType.APPLICATION_JSON),
        HttpStatus.GONE,
        WebErrorCode.REQUEST_FAILED.getCode());
  }

  @Test
  @DisplayName("an unexpected exception gets SX-WEB-1005 and none of its message")
  void unexpectedException() throws Exception {
    var response = get("/orders/broken", MediaType.APPLICATION_JSON);

    assertProblem(
        response, HttpStatus.INTERNAL_SERVER_ERROR, WebErrorCode.INTERNAL_ERROR.getCode());
    assertThat(response.body())
        .doesNotContain(OrdersApplication.INTERNAL_DETAIL, "IllegalStateException", "\tat ");
  }

  @Test
  @DisplayName("the service's own Problem Details body is kept, with only the IDs added")
  void serviceProblemDetailKept() throws Exception {
    var response = get("/orders/refund", MediaType.APPLICATION_JSON);

    assertThat(response.statusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED.value());
    assertThat(JsonPath.<String>read(response.body(), "$.detail"))
        .isEqualTo("The refund was refused");
    assertThat(JsonPath.<String>read(response.body(), TRACE_ID_PATH)).isNotBlank();
    assertThat(response.body()).doesNotContain("\"" + WebConstants.CODE + "\"");
  }

  @Test
  @DisplayName("the service's own body of another shape is not touched")
  void serviceCustomBodyUntouched() throws Exception {
    var response = get("/orders/legacy", MediaType.APPLICATION_JSON);

    assertThat(response.statusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value());
    assertThat(JsonPath.<Map<String, Object>>read(response.body(), "$"))
        .isEqualTo(Map.of("error", "legacy-format"));
  }

  @Test
  @DisplayName("a browser gets an HTML page with the trace ID as its reference")
  void browserGetsReferencePage() throws Exception {
    var response = get("/no-such-path", MediaType.TEXT_HTML);

    assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
    assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
        .hasValueSatisfying(type -> assertThat(type).startsWith(MediaType.TEXT_HTML_VALUE));
    assertThat(response.body()).contains("Not Found").containsPattern("Reference: <code>\\w{32}");
  }

  @Test
  @DisplayName("a caller that accepts anything gets the Problem Details body")
  void anyMediaTypeGetsProblemDetails() throws Exception {
    assertProblem(get("/orders/duplicate", MediaType.ALL), HttpStatus.CONFLICT, "ORD-0001");
  }

  @Test
  @DisplayName("a browser gets the HTML page with the mapped status for a StratumException")
  void browserGetsReferencePageForStratumException() throws Exception {
    var response = get("/orders/duplicate", MediaType.TEXT_HTML);

    assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(response.body()).contains("Conflict").containsPattern("Reference: <code>\\w{32}");
  }

  @Test
  @DisplayName("a controller's own sendError gets the error body with the trace ID")
  void sendErrorKeepsTraceId() throws Exception {
    assertProblem(
        get("/orders/missing", MediaType.APPLICATION_JSON),
        HttpStatus.NOT_FOUND,
        WebErrorCode.NOT_FOUND.getCode());
  }

  @Test
  @DisplayName("a filter that fails gets SX-WEB-1005 with the trace ID and none of its message")
  void failingFilterKeepsTraceId() throws Exception {
    var response = get(OrdersApplication.FailingFilter.FAILING_PATH, MediaType.APPLICATION_JSON);

    assertProblem(
        response, HttpStatus.INTERNAL_SERVER_ERROR, WebErrorCode.INTERNAL_ERROR.getCode());
    assertThat(response.body()).doesNotContain(OrdersApplication.INTERNAL_DETAIL);
  }

  @Test
  @DisplayName("a filter that rejects the caller with 401 gets SX-WEB-1004 with the trace ID")
  void rejectingFilterKeepsTraceId() throws Exception {
    assertProblem(
        get(OrdersApplication.FailingFilter.REJECTING_PATH, MediaType.APPLICATION_JSON),
        HttpStatus.UNAUTHORIZED,
        WebErrorCode.REQUEST_FAILED.getCode());
  }

  /** Checks a Problem Details answer: its status, media type, code and trace ID. */
  private void assertProblem(HttpResponse<String> response, HttpStatus status, String code) {
    assertThat(response.statusCode()).isEqualTo(status.value());
    assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
        .hasValue(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    assertThat(JsonPath.<String>read(response.body(), CODE_PATH)).isEqualTo(code);
    assertThat(JsonPath.<Integer>read(response.body(), "$.status")).isEqualTo(status.value());
    assertThat(JsonPath.<String>read(response.body(), TRACE_ID_PATH)).matches("\\p{XDigit}{32}");
  }

  /** Sends a GET that accepts the given media type. */
  private HttpResponse<String> get(String path, MediaType accepted)
      throws IOException, InterruptedException {
    return send(HttpRequest.newBuilder(uri(path)).header(HttpHeaders.ACCEPT, accepted.toString()));
  }

  /** Sends a request to the service and reads the body as text. */
  private HttpResponse<String> send(HttpRequest.Builder request)
      throws IOException, InterruptedException {
    return HTTP_CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** Returns the address of a path on the service. */
  private URI uri(String path) {
    var port = ((WebServerApplicationContext) service).getWebServer().getPort();
    return URI.create("http://localhost:" + port + path);
  }
}
