package com.exepex.stratum.resilience.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.resilience.ResilienceConstants;
import com.exepex.stratum.resilience.testsupport.CapturedLines;
import com.exepex.stratum.resilience.testsupport.HeldRequests;
import com.exepex.stratum.resilience.testsupport.RefusalAssertions;
import com.exepex.stratum.resilience.testsupport.ResilienceTestData;
import com.exepex.stratum.resilience.testsupport.RouteMeterReader;
import com.exepex.stratum.resilience.testsupport.RouteRequests;
import com.exepex.stratum.test.WebTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(
    classes = LimitedApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      ResilienceConstants.INCOMING_ROUTES + "[/held/**].max-concurrent=1",
      ResilienceConstants.RETRY_AFTER + "=1500ms",
      "stratum.web.timeout.routes[/held/slow]=300ms"
    })
@DisplayName("A refused request is answered by the web starter, whatever the service's set-up")
class RefusalAnswerIntegrationTest {

  /** A path of the full route. */
  private static final String REFUSED_PATH = "/held/quick";

  /** The Retry-After of 1500ms, rounded up to whole seconds. */
  private static final String RETRY_AFTER = "2";

  /** The log lines of the error response factory. */
  @RegisterExtension
  private final CapturedLines factoryLines = CapturedLines.ofErrorResponseFactory();

  @LocalServerPort private int port;

  @Autowired private HeldRequests held;

  @Autowired private MeterRegistry meterRegistry;

  /** Returns the answer a GET to the path gets, with the headers, while the route is full. */
  private HttpResponse<String> getWhileFull(String path, String... headers) throws Exception {
    return RouteRequests.whileFull(
        port,
        HeldController.HELD_PATH,
        held,
        () -> WebTestSupport.getFromService(port, path, headers));
  }

  @Nested
  @DisplayName("with Stratum's defaults")
  class Defaults {

    @Test
    @DisplayName("a browser gets the error page with 503 and Retry-After")
    void browserGetsErrorPage() throws Exception {
      var response = getWhileFull(REFUSED_PATH, "Accept", "text/html");

      assertThat(response.statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
      assertThat(response.headers().firstValue("Content-Type"))
          .hasValueSatisfying(type -> assertThat(type).startsWith("text/html"));
      RefusalAssertions.checkRetryAfter(response, RETRY_AFTER);
    }

    @Test
    @DisplayName("a request an earlier filter answers with 401 is not counted")
    void deniedRequestNotCounted() throws Exception {
      var response = getWhileFull(REFUSED_PATH, LimitedApplication.DENY_HEADER, "yes");

      assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
      assertThat(response.headers().allValues(RefusalAssertions.RETRY_AFTER)).isEmpty();
    }

    @Test
    @DisplayName("a CORS preflight is not counted")
    void preflightNotCounted() throws Exception {
      var preflight =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + REFUSED_PATH))
              .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
              .header("Origin", "https://shop.example")
              .header("Access-Control-Request-Method", "GET")
              .build();
      try (var client = HttpClient.newHttpClient()) {
        var response =
            RouteRequests.whileFull(
                port,
                HeldController.HELD_PATH,
                held,
                () -> client.send(preflight, HttpResponse.BodyHandlers.ofString()));

        assertThat(response.statusCode()).isNotEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(response.headers().allValues(RefusalAssertions.RETRY_AFTER)).isEmpty();
      }
    }

    @Test
    @DisplayName("a request stratum.web.timeout cuts off gives its permit back")
    void timeoutReleases() throws Exception {
      var meters = new RouteMeterReader(meterRegistry, "/held/**");

      assertThat(WebTestSupport.getFromService(port, "/held/slow").statusCode())
          .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());

      await().atMost(RouteRequests.WAIT).until(() -> meters.active() == 0);
      assertThat(WebTestSupport.getFromService(port, REFUSED_PATH).statusCode())
          .isEqualTo(HttpStatus.OK.value());
    }
  }

  @Nested
  @DisplayName("with the status mapped to 429")
  @TestPropertySource(properties = ResilienceTestData.REFUSED_STATUS + "=429")
  class MappedStatus {

    @Test
    @DisplayName("the caller gets 429, Stratum's body and Retry-After, and no line is logged")
    void mappedStatus() throws Exception {
      RefusalAssertions.checkRefused(
          getWhileFull(REFUSED_PATH), HttpStatus.TOO_MANY_REQUESTS.value(), RETRY_AFTER);
      assertThat(factoryLines.lines()).isEmpty();
    }
  }

  @Nested
  @DisplayName("with Spring's Problem Details on")
  @TestPropertySource(
      properties = {
        ResilienceTestData.REFUSED_STATUS + "=429",
        "spring.mvc.problemdetails.enabled=true"
      })
  class SpringProblemDetails {

    @Test
    @DisplayName("the caller still gets the mapped status and Stratum's code")
    void mappedStatusKept() throws Exception {
      RefusalAssertions.checkRefused(
          getWhileFull(REFUSED_PATH), HttpStatus.TOO_MANY_REQUESTS.value(), RETRY_AFTER);
    }
  }

  @Nested
  @DisplayName("with the service's own ResponseEntityExceptionHandler")
  @TestPropertySource(
      properties = {
        ResilienceTestData.REFUSED_STATUS + "=429",
        ServiceErrorHandlers.SPRING_HANDLER + "=true"
      })
  class ServiceSpringHandler {

    @Test
    @DisplayName("the caller still gets the mapped status and Stratum's code")
    void mappedStatusKept() throws Exception {
      RefusalAssertions.checkRefused(
          getWhileFull(REFUSED_PATH), HttpStatus.TOO_MANY_REQUESTS.value(), RETRY_AFTER);
    }
  }

  @Nested
  @DisplayName("with the service's catch-all @ExceptionHandler")
  @TestPropertySource(properties = ServiceErrorHandlers.CATCH_ALL + "=true")
  class ServiceCatchAll {

    @Test
    @DisplayName("the service's handler answers first, with its own status; Retry-After stays")
    void serviceStatusWins() throws Exception {
      var response = getWhileFull(REFUSED_PATH);

      assertThat(response.statusCode()).isEqualTo(ServiceErrorHandlers.CATCH_ALL_STATUS.value());
      RefusalAssertions.checkRetryAfter(response, RETRY_AFTER);
    }
  }

  @Nested
  @DisplayName("in backward-compatible mode")
  @TestPropertySource(properties = "stratum.backward-compatible=true")
  class BackwardCompatible {

    @Test
    @DisplayName("the limit applies and the caller gets 503 with Stratum's body")
    void limitApplies() throws Exception {
      RefusalAssertions.checkRefused(
          getWhileFull(REFUSED_PATH), HttpStatus.SERVICE_UNAVAILABLE.value(), RETRY_AFTER);
    }
  }

  @Nested
  @DisplayName("with the web module off")
  @TestPropertySource(properties = "stratum.web.enabled=false")
  class WebModuleOff {

    @Test
    @DisplayName("the caller gets 503 with Spring Boot's body and one Retry-After")
    void springBootBody() throws Exception {
      var response = getWhileFull(REFUSED_PATH);

      assertThat(response.statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
      assertThat(response.body()).contains("\"status\":503").doesNotContain("SX-RESILIENCE");
      RefusalAssertions.checkRetryAfter(response, RETRY_AFTER);
    }
  }

  @Nested
  @DisplayName("with a status map that has no status for the code")
  @TestPropertySource(properties = ServiceErrorHandlers.NO_STATUSES + "=true")
  class NoStatusMapped {

    @Test
    @DisplayName("the caller gets 500, and one ERROR line says the code has no status")
    void unmappedCodeLogged() throws Exception {
      var response = getWhileFull(REFUSED_PATH);

      assertThat(response.statusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
      assertThat(factoryLines.onlyLine().getFormattedMessage())
          .contains(ResilienceTestData.REFUSED_CODE, "has no status in");
    }
  }
}
