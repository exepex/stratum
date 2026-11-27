package com.exepex.stratum.resilience.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.resilience.ResilienceConstants;
import com.exepex.stratum.resilience.testsupport.HeldRequests;
import com.exepex.stratum.resilience.testsupport.RouteMeterReader;
import com.exepex.stratum.resilience.testsupport.RouteRequests;
import com.exepex.stratum.test.WebTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

@SpringBootTest(
    classes = LimitedApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      ResilienceConstants.INCOMING_ROUTES + "[/release/**].max-concurrent=1",
      "spring.mvc.async.request-timeout=300ms"
    })
@DisplayName("A request gives its permit back however it ends")
class PermitReleaseIntegrationTest {

  /**
   * Paths whose request fails: on the request thread, in a Callable, and by running out of time.
   */
  private static final List<String> FAILING_PATHS =
      List.of("/release/fails", "/release/callable-fails", "/release/never");

  /** How long the caller waits before it gives up and disconnects. */
  private static final Duration CALLER_TIMEOUT = Duration.ofMillis(300);

  @LocalServerPort private int port;

  @Autowired private HeldRequests held;

  @Autowired private MeterRegistry meterRegistry;

  /** The route's meters. */
  private RouteMeterReader meters;

  /** Reads the route's meters. */
  @BeforeEach
  void readMeters() {
    meters = new RouteMeterReader(meterRegistry, "/release/**");
  }

  @ParameterizedTest(name = "{0}")
  @FieldSource("FAILING_PATHS")
  @DisplayName("a failed request gives its permit back, so the next request runs")
  void failedRequestReleases(String path) throws Exception {
    assertThat(WebTestSupport.getFromService(port, path).statusCode())
        .isGreaterThanOrEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());

    await().atMost(RouteRequests.WAIT).until(() -> meters.active() == 0);
    assertThat(WebTestSupport.getFromService(port, "/release/quick").statusCode())
        .isEqualTo(HttpStatus.OK.value());
  }

  @Test
  @DisplayName("a caller that disconnects leaves the permit held until the work ends")
  void disconnectHoldsUntilWorkEnds() {
    held.close();
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/release/held"))
            .timeout(CALLER_TIMEOUT)
            .build();
    try (var client = HttpClient.newHttpClient()) {
      assertThatExceptionOfType(HttpTimeoutException.class)
          .isThrownBy(() -> client.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    assertThat(held.running()).isEqualTo(1);
    assertThat(meters.active()).isEqualTo(1);
    held.open();
    await().atMost(RouteRequests.WAIT).until(() -> meters.active() == 0);
  }
}
