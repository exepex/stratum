package com.exepex.stratum.resilience.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.resilience.ResilienceConstants;
import com.exepex.stratum.resilience.testsupport.HeldRequests;
import com.exepex.stratum.resilience.testsupport.RefusalAssertions;
import com.exepex.stratum.resilience.testsupport.RouteRequests;
import com.exepex.stratum.test.WebTestSupport;
import com.exepex.stratum.web.WebErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

@SpringBootTest(
    classes = LimitedApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "stratum.logging.enabled=true")
@DisplayName("A bundle service that sets no route behaves as before")
class NoRouteIntegrationTest {

  /** Requests sent at once, as a busy service gets them. */
  private static final int REQUESTS = 30;

  @LocalServerPort private int port;

  @Autowired private HeldRequests held;

  @Autowired private MeterRegistry meterRegistry;

  @Test
  @DisplayName("30 requests at once all run, with no Retry-After and no resilience meter")
  void everyRequestRuns() {
    held.close();
    var answers = RouteRequests.sendAtOnce(port, "/limited/sync", REQUESTS);
    await().atMost(RouteRequests.WAIT).until(() -> held.running() == REQUESTS);
    held.open();

    assertThat(answers.stream().map(CompletableFuture::join))
        .hasSize(REQUESTS)
        .allSatisfy(this::assertRan);
    assertThat(meterRegistry.find(ResilienceConstants.REFUSED_METER).meters()).isEmpty();
    assertThat(meterRegistry.find(ResilienceConstants.ACTIVE_METER).meters()).isEmpty();
  }

  /** Checks that the request ran: HttpStatus.OK.value(), with no Retry-After. */
  private void assertRan(HttpResponse<String> response) {
    assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
    assertThat(response.headers().allValues(RefusalAssertions.RETRY_AFTER)).isEmpty();
  }

  @Test
  @DisplayName("an unknown path still gets the web starter's 404 body")
  void unknownPathUnchanged() throws Exception {
    WebTestSupport.checkProblemDetails(
        WebTestSupport.getFromService(port, "/orders/missing"),
        HttpStatus.NOT_FOUND.value(),
        WebErrorCode.NOT_FOUND.getCode());
  }

  @Test
  @DisplayName("the health of a check that needs other beans is up: no bean cycle at startup")
  void healthUp() throws Exception {
    var response = WebTestSupport.getFromService(port, "/actuator/health");

    assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
    assertThat(response.body()).contains("\"UP\"");
  }
}
