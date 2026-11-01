package com.exepex.stratum.webflux.it;

import com.exepex.stratum.test.BackwardCompatibleErrorChecks;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

@SpringBootTest(
    classes = OrdersApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "stratum.web.enabled=true",
      "stratum.web.error.statuses.ORD-0001=409",
      "spring.web.error.include-message=always",
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

  /** A route of the service that fails with an exception Spring answers with 500. */
  private static final String BROKEN_ROUTE = "/orders/broken";

  /** The media type of Spring Boot's JSON body. */
  private static final String JSON = "application/json";

  /** The port the service listens on. */
  @LocalServerPort private int port;

  /** Where the counter of Spring Boot's bodies is kept. */
  @Autowired private MeterRegistry meterRegistry;

  @Nested
  @DisplayName("an error Spring answers")
  class SpringError {

    @Test
    @DisplayName("keeps Spring Boot's body and media type for a consumer that has not opted in")
    void keepsBootBody() throws Exception {
      BackwardCompatibleErrorChecks.checkBootBody(port, UNKNOWN_PATH);
    }

    @Test
    @DisplayName("keeps Spring Boot's body with an ErrorResponseException's own status, 400")
    void errorResponseKeepsItsStatus() throws Exception {
      BackwardCompatibleErrorChecks.checkBootBodyStatus(
          port,
          OrdersApplication.INVALID_STATE_PATH,
          HttpStatus.BAD_REQUEST.value(),
          HttpStatus.BAD_REQUEST.getReasonPhrase());
    }

    @Test
    @DisplayName("gives an unnamed status, 499, the phrase Spring MVC gives: Http Status 499")
    void unlistedStatusKeepsBootPhrase() throws Exception {
      BackwardCompatibleErrorChecks.checkBootBodyStatus(
          port,
          OrdersApplication.UNLISTED_STATUS_PATH,
          OrdersApplication.UNLISTED_STATUS,
          "Http Status " + OrdersApplication.UNLISTED_STATUS);
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

  @Test
  @DisplayName("a StratumException wrapped in an exception with no status gets Stratum's body too")
  void wrappedStratumExceptionGetsStandardBody() throws Exception {
    BackwardCompatibleErrorChecks.checkCodedFailureBody(
        port, "/orders/duplicate-wrapped", HttpStatus.CONFLICT.value(), "ORD-0001");
  }

  @Nested
  @DisplayName("the counter of Spring Boot's bodies")
  class BootBodyCounter {

    @Test
    @DisplayName("counts an answer with Spring Boot's body under the route it matched")
    void countsRoute() throws Exception {
      BackwardCompatibleErrorChecks.checkCounted(
          port, BROKEN_ROUTE, JSON, () -> countOf(BROKEN_ROUTE), 1);
    }

    @Test
    @DisplayName("counts an unknown path under UNKNOWN")
    void countsUnknownPath() throws Exception {
      BackwardCompatibleErrorChecks.checkCounted(
          port, UNKNOWN_PATH, JSON, () -> countOf(WebConstants.UNKNOWN_ROUTE), 1);
    }

    @Test
    @DisplayName("does not count a consumer that opted in")
    void skipsOptedInConsumer() throws Exception {
      BackwardCompatibleErrorChecks.checkCounted(
          port, BROKEN_ROUTE, "application/problem+json", () -> countOf(BROKEN_ROUTE), 0);
    }

    @Test
    @DisplayName("does not count a browser, which gets Stratum's page in both modes")
    void skipsBrowser() throws Exception {
      BackwardCompatibleErrorChecks.checkCounted(
          port, UNKNOWN_PATH, "text/html", () -> countOf(WebConstants.UNKNOWN_ROUTE), 0);
    }
  }

  /** Returns how many answers kept Spring Boot's body on a route, 0 before the first. */
  private double countOf(String route) {
    return Optional.ofNullable(
            meterRegistry.find(WebConstants.BOOT_ERROR_BODY_COUNTER).tag("route", route).counter())
        .map(Counter::count)
        .orElse(0.0);
  }
}
