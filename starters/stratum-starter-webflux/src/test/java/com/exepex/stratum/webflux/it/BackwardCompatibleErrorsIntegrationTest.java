package com.exepex.stratum.webflux.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.test.StarterTestSupport;
import com.exepex.stratum.web.WebErrorCode;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

@SpringBootTest(
    classes = OrdersApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"stratum.web.enabled=true", "stratum.web.error.statuses.ORD-0001=409"})
@DisplayName(
    "With stratum.web.backward-compatible left at true, existing consumers keep their body")
class BackwardCompatibleErrorsIntegrationTest {

  /** A path the service does not have. */
  private static final String UNKNOWN_PATH = "/no-such-path";

  /** The path that fails with a StratumException whose code the service maps to 409. */
  private static final String DUPLICATE_PATH = "/orders/duplicate";

  /** The port the service listens on. */
  @LocalServerPort private int port;

  @Nested
  @DisplayName("an error Spring answers")
  class SpringError {

    @Test
    @DisplayName("keeps Spring Boot's body and media type for a consumer that has not opted in")
    void keepsBootBody() throws Exception {
      var response =
          StarterTestSupport.getFromService(
              port, UNKNOWN_PATH, HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);

      assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
      assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
          .hasValueSatisfying(
              type -> assertThat(type).startsWith(MediaType.APPLICATION_JSON_VALUE));
      assertThat(JsonPath.<String>read(response.body(), "$.error")).isEqualTo("Not Found");
      assertThat(JsonPath.<String>read(response.body(), "$.path")).isEqualTo(UNKNOWN_PATH);
      assertThat(response.body()).doesNotContain("\"code\"");
    }

    @Test
    @DisplayName("gets Stratum's body for a consumer that asks for application/problem+json")
    void optedInConsumerGetsStandardBody() throws Exception {
      StarterTestSupport.checkProblemDetails(
          StarterTestSupport.getFromService(
              port,
              UNKNOWN_PATH,
              HttpHeaders.ACCEPT,
              MediaType.APPLICATION_JSON_VALUE + ", " + MediaType.APPLICATION_PROBLEM_JSON_VALUE),
          HttpStatus.NOT_FOUND.value(),
          WebErrorCode.NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("gives a browser Stratum's page, titled from Spring Boot's body")
    void browserGetsReferencePage() throws Exception {
      var response =
          StarterTestSupport.getFromService(
              port, UNKNOWN_PATH, HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE);

      assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
      assertThat(response.body()).contains("<title>Not Found</title>");
    }
  }

  @Test
  @DisplayName("a StratumException, which has no older body, always gets Stratum's body")
  void stratumExceptionGetsStandardBody() throws Exception {
    StarterTestSupport.checkProblemDetails(
        StarterTestSupport.getFromService(
            port, DUPLICATE_PATH, HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE),
        HttpStatus.CONFLICT.value(),
        "ORD-0001");
  }
}
