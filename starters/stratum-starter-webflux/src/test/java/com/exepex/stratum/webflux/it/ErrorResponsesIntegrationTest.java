package com.exepex.stratum.webflux.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.test.StarterTestSupport;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebErrorCode;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
    classes = OrdersApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "stratum.web.enabled=true",
      "stratum.web.error.statuses.ORD-0001=409",
      "stratum.observability.enabled=true",
      "management.tracing.sampling.probability=1.0",
      "management.tracing.export.enabled=false",
      "management.otlp.metrics.export.enabled=false"
    })
@DisplayName("A WebFlux service with the web module on answers every error the same way")
class ErrorResponsesIntegrationTest {

  /** The member of the error body that holds the trace ID. */
  private static final String TRACE_ID_PATH = "$." + LoggingContextConstants.TRACE_ID;

  /** The path that fails with a code the service maps to 409. */
  private static final String DUPLICATE_PATH = "/orders/duplicate";

  /** The code the service maps to 409. */
  private static final String DUPLICATE_ORDER_CODE = "ORD-0001";

  /** Calls the service. */
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

  /** The port the service listens on. */
  @LocalServerPort private int port;

  @Nested
  @DisplayName("when the request fails with a StratumException")
  class StratumExceptions {

    @Test
    @DisplayName(
        "a mapped StratumException gets its status, code and trace ID, and no stack trace or path")
    void mappedStratumException() throws Exception {
      var path = DUPLICATE_PATH;
      var response = get(path, MediaType.APPLICATION_JSON);

      StarterTestSupport.checkProblemDetails(
          response, HttpStatus.CONFLICT.value(), DUPLICATE_ORDER_CODE);
      assertThat(JsonPath.<String>read(response.body(), "$.detail"))
          .isEqualTo("Order already exists: order 42");
      assertThat(JsonPath.<String>read(response.body(), "$.severity")).isEqualTo("ERROR");
      assertThat(
              JsonPath.<String>read(response.body(), "$." + LoggingContextConstants.CORRELATION_ID))
          .isNotBlank();
      assertThat(response.body()).doesNotContain(path);
    }

    @Test
    @DisplayName("each request gets its own trace ID, never one left on the thread by another")
    void traceIdPerRequest() throws Exception {
      var first = get(DUPLICATE_PATH, MediaType.APPLICATION_JSON);
      var second = get(DUPLICATE_PATH, MediaType.APPLICATION_JSON);

      assertThat(JsonPath.<String>read(first.body(), TRACE_ID_PATH))
          .isNotEqualTo(JsonPath.<String>read(second.body(), TRACE_ID_PATH));
    }

    @Test
    @DisplayName("an unmapped StratumException gets 500 with its own code, and is logged once")
    void unmappedStratumException(CapturedOutput output) throws Exception {
      StarterTestSupport.checkProblemDetails(
          get("/orders/unpriced", MediaType.APPLICATION_JSON),
          HttpStatus.INTERNAL_SERVER_ERROR.value(),
          "ORD-0003");
      assertThat(output.getAll())
          .containsOnlyOnce("StratumException: ORD-0003")
          .doesNotContain("500 Server Error");
    }

    @Test
    @DisplayName("a caller that accepts anything gets the Problem Details body")
    void anyMediaTypeGetsProblemDetails() throws Exception {
      StarterTestSupport.checkProblemDetails(
          get(DUPLICATE_PATH, MediaType.ALL), HttpStatus.CONFLICT.value(), DUPLICATE_ORDER_CODE);
    }

    @Test
    @DisplayName(
        "an exception that implements FailureWithErrorCode gets the same status, code and detail")
    void failureWithErrorCode() throws Exception {
      var response = get("/orders/duplicate-existing", MediaType.APPLICATION_JSON);

      StarterTestSupport.checkProblemDetails(
          response, HttpStatus.CONFLICT.value(), DUPLICATE_ORDER_CODE);
      assertThat(JsonPath.<String>read(response.body(), "$.detail"))
          .isEqualTo("Order already exists: order 42");
    }
  }

  @Test
  @DisplayName("a FailureWithErrorCode with @ResponseStatus gets its code's status and is logged")
  void failureWithErrorCodeWinsOverResponseStatus(CapturedOutput output) throws Exception {
    StarterTestSupport.checkProblemDetails(
        get("/orders/duplicate-annotated", MediaType.APPLICATION_JSON),
        HttpStatus.CONFLICT.value(),
        DUPLICATE_ORDER_CODE);
    assertThat(output).contains(DUPLICATE_ORDER_CODE + ": Order already exists: order 42");
  }

  @Test
  @DisplayName("a code in the cause of a plain exception gets the code's status and is logged")
  void codeOfCauseReplaces500(CapturedOutput output) throws Exception {
    StarterTestSupport.checkProblemDetails(
        get("/orders/duplicate-wrapped", MediaType.APPLICATION_JSON),
        HttpStatus.CONFLICT.value(),
        DUPLICATE_ORDER_CODE);
    assertThat(output).contains(DUPLICATE_ORDER_CODE + ": Order already exists: order 42");
  }

  @ParameterizedTest
  @CsvSource({
    "/orders/duplicate-with-status/404, 404, SX-WEB-1003",
    "/orders/duplicate-with-status/500, 500, SX-WEB-1005",
    "/orders/duplicate-gone, 410, SX-WEB-1004"
  })
  @DisplayName(
      "a code in the cause of a status Spring is given, even an explicit 500 or @ResponseStatus,"
          + " keeps Spring's answer")
  void springStatusWinsOverCodeOfCause(String path, int status, String code) throws Exception {
    StarterTestSupport.checkProblemDetails(get(path, MediaType.APPLICATION_JSON), status, code);
  }

  @Nested
  @DisplayName("when Spring rejects the request or it fails unexpectedly")
  class SpringFailures {

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

      StarterTestSupport.checkProblemDetails(
          response, HttpStatus.BAD_REQUEST.value(), WebErrorCode.INVALID_FIELDS.getCode());
      assertThat(JsonPath.<Object>read(response.body(), "$.details[*].field").toString())
          .contains("quantity", "account");
      assertThat(response.body()).doesNotContain(OrdersApplication.IBAN);
    }

    @Test
    @DisplayName("an invalid parameter gets SX-WEB-1001 naming the parameter")
    void invalidParameter() throws Exception {
      var response = get("/orders/search?quantity=0", MediaType.APPLICATION_JSON);

      StarterTestSupport.checkProblemDetails(
          response, HttpStatus.BAD_REQUEST.value(), WebErrorCode.INVALID_FIELDS.getCode());
      assertThat(JsonPath.<Object>read(response.body(), "$.details[0].field"))
          .isEqualTo("quantity");
    }

    @Test
    @DisplayName("a path variable of the wrong type gets SX-WEB-1002")
    void unreadableRequest() throws Exception {
      StarterTestSupport.checkProblemDetails(
          get("/orders/abc", MediaType.APPLICATION_JSON),
          HttpStatus.BAD_REQUEST.value(),
          WebErrorCode.UNREADABLE_REQUEST.getCode());
    }

    @Test
    @DisplayName("an unknown path gets SX-WEB-1003 with the trace ID")
    void unknownPath() throws Exception {
      StarterTestSupport.checkProblemDetails(
          get("/no-such-path", MediaType.APPLICATION_JSON),
          HttpStatus.NOT_FOUND.value(),
          WebErrorCode.NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("an exception with @ResponseStatus gets its status and SX-WEB-1004")
    void responseStatusException() throws Exception {
      StarterTestSupport.checkProblemDetails(
          get("/orders/gone", MediaType.APPLICATION_JSON),
          HttpStatus.GONE.value(),
          WebErrorCode.REQUEST_FAILED.getCode());
    }

    @Test
    @DisplayName("an unexpected exception gets SX-WEB-1005 and none of its message")
    void unexpectedException() throws Exception {
      var response = get("/orders/broken", MediaType.APPLICATION_JSON);

      StarterTestSupport.checkProblemDetails(
          response,
          HttpStatus.INTERNAL_SERVER_ERROR.value(),
          WebErrorCode.INTERNAL_ERROR.getCode());
      assertThat(response.body())
          .doesNotContain(OrdersApplication.INTERNAL_DETAIL, "IllegalStateException", "\tat ");
    }
  }

  @Nested
  @DisplayName("when the service's own exception handler answers")
  class ServiceHandling {

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
  }

  @Nested
  @DisplayName("when a browser gets the error")
  class BrowserPage {

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
    @DisplayName("a browser gets the HTML page with the mapped status for a StratumException")
    void browserGetsReferencePageForStratumException() throws Exception {
      var response = get(DUPLICATE_PATH, MediaType.TEXT_HTML);

      assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
      assertThat(response.body()).contains("Conflict").containsPattern("Reference: <code>\\w{32}");
    }
  }

  @Nested
  @DisplayName("when a web filter stops the request")
  class FilterFailures {

    @Test
    @DisplayName(
        "a web filter that fails gets SX-WEB-1005 with the trace ID and none of its message")
    void failingFilterKeepsTraceId() throws Exception {
      var response = get(OrdersApplication.FailingFilter.FAILING_PATH, MediaType.APPLICATION_JSON);

      StarterTestSupport.checkProblemDetails(
          response,
          HttpStatus.INTERNAL_SERVER_ERROR.value(),
          WebErrorCode.INTERNAL_ERROR.getCode());
      assertThat(response.body()).doesNotContain(OrdersApplication.INTERNAL_DETAIL);
    }

    @Test
    @DisplayName("a web filter that rejects the caller with 401 gets SX-WEB-1004 with the trace ID")
    void rejectingFilterKeepsTraceId() throws Exception {
      StarterTestSupport.checkProblemDetails(
          get(OrdersApplication.FailingFilter.REJECTING_PATH, MediaType.APPLICATION_JSON),
          HttpStatus.UNAUTHORIZED.value(),
          WebErrorCode.REQUEST_FAILED.getCode());
    }
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
    return URI.create("http://localhost:" + port + path);
  }
}
