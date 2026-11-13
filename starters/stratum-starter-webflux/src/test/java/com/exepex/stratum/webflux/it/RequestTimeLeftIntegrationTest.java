package com.exepex.stratum.webflux.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.request.RequestTimeLeft;
import com.exepex.stratum.test.WebTestSupport;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.webflux.it.TimeLeftApplication.Answers;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@SpringBootTest(
    classes = TimeLeftApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      WebConstants.TIMEOUT_DEFAULT_LIMIT + "=30s",
      WebConstants.TIMEOUT_ROUTES + "[/time-left/**]=2000ms",
      WebConstants.TIMEOUT_ROUTES + "[/unlimited/**]=0",
      "stratum.observability.enabled=false"
    })
@Timeout(20)
@DisplayName("On WebFlux, the service reads how long the current request has left")
class RequestTimeLeftIntegrationTest {

  /** The limit of the time-left route, in milliseconds. */
  private static final long ROUTE_LIMIT_MILLIS = 2000;

  /** The limit of the method with its own, in milliseconds. */
  private static final long OWN_LIMIT_MILLIS = 20_000;

  /** How long a test waits for work on another thread. */
  private static final Duration WAIT = Duration.ofSeconds(5);

  @LocalServerPort private int port;

  @Autowired private RequestTimeLeft requestTimeLeft;

  @Autowired private TimeLeftApplication.TimeLeftController controller;

  @Nested
  @DisplayName("during the request")
  class DuringRequest {

    @Test
    @DisplayName("the handler's work sees the route's limit minus the time the request has run")
    void routeLimit() throws Exception {
      assertTimeLeftUpTo(WebTestSupport.getFromService(port, "/time-left"), ROUTE_LIMIT_MILLIS);
    }

    @Test
    @DisplayName("a blocking call on the bounded elastic scheduler sees the time left")
    void blockingCall() throws Exception {
      assertTimeLeftUpTo(
          WebTestSupport.getFromService(port, "/time-left/blocking"), ROUTE_LIMIT_MILLIS);
    }

    @Test
    @DisplayName("work moved to another scheduler with publishOn sees the time left")
    void publishOn() throws Exception {
      assertTimeLeftUpTo(
          WebTestSupport.getFromService(port, "/time-left/publish-on"), ROUTE_LIMIT_MILLIS);
    }

    @Test
    @DisplayName("a method's @ResponseTimeout is seen as soon as it applies")
    void ownLimit() throws Exception {
      var answer = WebTestSupport.getFromService(port, "/time-left/own-limit");

      assertTimeLeftUpTo(answer, OWN_LIMIT_MILLIS);
      assertThat(Long.parseLong(answer.body())).isGreaterThan(ROUTE_LIMIT_MILLIS);
    }

    @Test
    @DisplayName("a route with a limit of 0 has no limit")
    void unlimitedRoute() throws Exception {
      var answer = WebTestSupport.getFromService(port, "/unlimited/time-left");

      assertThat(answer.statusCode()).isEqualTo(HttpStatus.OK.value());
      assertThat(answer.body()).isEqualTo(Answers.NO_LIMIT);
    }
  }

  @Nested
  @DisplayName("after the request")
  class AfterRequest {

    @Test
    @DisplayName("work that kept the request's deadline sees no limit once the response started")
    void keptDeadline() throws Exception {
      WebTestSupport.getFromService(port, "/time-left/kept");

      var kept = controller.getKeptDeadline().get(WAIT.toSeconds(), TimeUnit.SECONDS);

      assertThat(kept.timeLeft()).isEmpty();
    }

    @Test
    @DisplayName("a scheduler thread that ran a request's work keeps no deadline")
    void noStaleDeadline() throws Exception {
      WebTestSupport.getFromService(port, "/time-left/blocking");

      var seen =
          Mono.fromCallable(requestTimeLeft::timeLeft)
              .subscribeOn(Schedulers.boundedElastic())
              .block(WAIT);

      assertThat(seen).isEmpty();
    }
  }

  /** Checks that the answer is a time left above zero and at most the limit. */
  private void assertTimeLeftUpTo(HttpResponse<String> answer, long limitMillis) {
    assertThat(answer.statusCode()).isEqualTo(HttpStatus.OK.value());
    assertThat(Long.parseLong(answer.body())).isPositive().isLessThanOrEqualTo(limitMillis);
  }
}
