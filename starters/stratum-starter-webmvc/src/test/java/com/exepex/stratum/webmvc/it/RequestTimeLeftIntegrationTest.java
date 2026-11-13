package com.exepex.stratum.webmvc.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.test.WebTestSupport;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.webmvc.it.TimeLeftApplication.Answers;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

@SpringBootTest(
    classes = TimeLeftApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      WebConstants.TIMEOUT_DEFAULT_LIMIT + "=30s",
      WebConstants.TIMEOUT_ROUTES
          + "[/time-left/**]="
          + RequestTimeLeftIntegrationTest.ROUTE_LIMIT_MILLIS
          + "ms",
      WebConstants.TIMEOUT_ROUTES + "[/unlimited/**]=0",
      WebConstants.TIMEOUT_ROUTES + "[/late/**]=200ms",
      // Off, so that only the web starter runs; the next line sets what it would set.
      "stratum.observability.enabled=false",
      // What the logging and observability starters set, so that @Async work keeps the context.
      "spring.task.execution.propagate-context=true"
    })
@Timeout(20)
@DisplayName("On Spring MVC, the service reads how long the current request has left")
class RequestTimeLeftIntegrationTest {

  /** The limit of the time-left route, in milliseconds. */
  static final long ROUTE_LIMIT_MILLIS = 2000;

  /** The limit of the method with its own, in milliseconds. */
  private static final long OWN_LIMIT_MILLIS = 20_000;

  @LocalServerPort private int port;

  @Autowired private TimeLeftApplication.AsyncWork asyncWork;

  @Autowired private TimeLeftApplication.TimeLeftController controller;

  @Nested
  @DisplayName("on the request's thread")
  class RequestThread {

    @Test
    @DisplayName("the time left is the route's limit minus the time the request has run")
    void routeLimit() throws Exception {
      assertTimeLeftUpTo(WebTestSupport.getFromService(port, "/time-left"), ROUTE_LIMIT_MILLIS);
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
      assertNoLimit(WebTestSupport.getFromService(port, "/unlimited/time-left"));
    }

    @Test
    @DisplayName("once the limit has passed, before the timeout is answered, no time is left")
    void passedLimit() throws Exception {
      var answer = WebTestSupport.getFromService(port, "/late/time-left");

      assertThat(answer.statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
      assertThat(
              Long.parseLong(
                  controller
                      .getSeenWhenLate()
                      .get(TimeLeftApplication.WAIT.toSeconds(), TimeUnit.SECONDS)))
          .isNotPositive();
    }
  }

  @Nested
  @DisplayName("on other threads")
  class OtherThreads {

    @Test
    @DisplayName("@Async work during the request sees the request's time left")
    void asyncDuringRequest() throws Exception {
      assertTimeLeftUpTo(
          WebTestSupport.getFromService(port, "/time-left/async"), ROUTE_LIMIT_MILLIS);
    }

    @Test
    @DisplayName("@Async work that runs after the response sees no limit")
    void asyncAfterResponse() throws Exception {
      var answer = WebTestSupport.getFromService(port, "/time-left/async-after-response");
      assertThat(answer.statusCode()).isEqualTo(HttpStatus.OK.value());

      assertThat(
              asyncWork
                  .releaseAfterResponse()
                  .get(TimeLeftApplication.WAIT.toSeconds(), TimeUnit.SECONDS))
          .isEqualTo(Answers.NO_LIMIT);
    }

    @Test
    @DisplayName("a Callable sees no limit once its handler has returned")
    void callable() throws Exception {
      assertNoLimit(WebTestSupport.getFromService(port, "/time-left/callable"));
    }

    @Test
    @DisplayName("a thread of the service's own, which completes a DeferredResult, sees no limit")
    void deferredResult() throws Exception {
      assertNoLimit(WebTestSupport.getFromService(port, "/time-left/deferred"));
    }

    @Test
    @DisplayName("@Async work outside any request, on the same pool, sees no limit")
    void asyncOutsideRequest() throws Exception {
      WebTestSupport.getFromService(port, "/time-left/async");

      assertThat(
              asyncWork.timeLeftNow().get(TimeLeftApplication.WAIT.toSeconds(), TimeUnit.SECONDS))
          .isEqualTo(Answers.NO_LIMIT);
    }
  }

  /** Checks that the answer is a time left above zero and at most the limit. */
  private void assertTimeLeftUpTo(HttpResponse<String> answer, long limitMillis) {
    assertThat(answer.statusCode()).isEqualTo(HttpStatus.OK.value());
    assertThat(Long.parseLong(answer.body())).isPositive().isLessThanOrEqualTo(limitMillis);
  }

  /** Checks that the answer says there is no limit. */
  private void assertNoLimit(HttpResponse<String> answer) {
    assertThat(answer.statusCode()).isEqualTo(HttpStatus.OK.value());
    assertThat(answer.body()).isEqualTo(Answers.NO_LIMIT);
  }
}
