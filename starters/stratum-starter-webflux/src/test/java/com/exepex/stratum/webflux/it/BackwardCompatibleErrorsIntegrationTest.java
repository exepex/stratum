package com.exepex.stratum.webflux.it;

import com.exepex.stratum.test.BackwardCompatibleErrorChecks;
import com.exepex.stratum.web.WebErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

@SpringBootTest(
    classes = OrdersApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "stratum.web.enabled=true",
      "stratum.web.error.statuses.ORD-0001=409",
      "spring.web.error.include-stacktrace=always",
      "spring.web.error.include-exception=true",
      "spring.web.error.include-message=always",
      "spring.web.error.include-binding-errors=always",
      "stratum.observability.enabled=true",
      "management.tracing.sampling.probability=1.0",
      "management.tracing.export.enabled=false",
      "management.otlp.metrics.export.enabled=false"
    })
@DisplayName(
    "With stratum.web.backward-compatible left at true, existing consumers keep their body")
class BackwardCompatibleErrorsIntegrationTest {

  /** A path the service does not have. */
  private static final String UNKNOWN_PATH = "/no-such-path";

  /** The port the service listens on. */
  @LocalServerPort private int port;

  @Nested
  @DisplayName("an error Spring answers")
  class SpringError {

    @Test
    @DisplayName("keeps Spring Boot's body, without diagnostics, for a consumer not opted in")
    void keepsBootBody() throws Exception {
      BackwardCompatibleErrorChecks.checkBootBody(port, UNKNOWN_PATH);
    }

    @Test
    @DisplayName("keeps Spring Boot's body for an invalid body, without the rejected value")
    void invalidBodyHidesValue() throws Exception {
      BackwardCompatibleErrorChecks.checkInvalidBodyHidesValue(
          port,
          "/orders",
          "{\"quantity\":0,\"account\":\"" + OrdersApplication.IBAN + "\"}",
          OrdersApplication.IBAN);
    }

    @Test
    @DisplayName("gets Stratum's body for a consumer that asks for application/problem+json")
    void optedInConsumerGetsStandardBody() throws Exception {
      BackwardCompatibleErrorChecks.checkOptedInBody(
          port, UNKNOWN_PATH, WebErrorCode.NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("gives a browser Stratum's page, titled from Spring Boot's body, with a reference")
    void browserGetsReferencePage() throws Exception {
      BackwardCompatibleErrorChecks.checkBrowserPage(port, UNKNOWN_PATH);
    }
  }

  @Test
  @DisplayName("a StratumException, which has no older body, always gets Stratum's body")
  void stratumExceptionGetsStandardBody() throws Exception {
    BackwardCompatibleErrorChecks.checkCodedFailureBody(
        port, "/orders/duplicate", HttpStatus.CONFLICT.value(), "ORD-0001");
  }
}
