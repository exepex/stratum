package com.exepex.stratum.webmvc.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.test.WebTestSupport;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.webmvc.it.TimeLeftApplication.Answers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

@Timeout(20)
@DisplayName(
    "On Spring MVC, whether @Async work sees the request's time left depends on its executor")
class RequestTimeLeftExecutorsIntegrationTest {

  /** The limit of the time-left route, in milliseconds. */
  private static final long ROUTE_LIMIT_MILLIS = 2000;

  /** Sets the time-left route's limit. */
  private static final String ROUTE_LIMIT =
      WebConstants.TIMEOUT_ROUTES + "[/time-left/**]=" + ROUTE_LIMIT_MILLIS + "ms";

  /**
   * Turns off the observability starter, which this module's tests have, so that only the web
   * starter runs.
   */
  private static final String OBSERVABILITY_OFF = "stratum.observability.enabled=false";

  @Nested
  @SpringBootTest(
      classes = TimeLeftApplication.class,
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = {ROUTE_LIMIT, OBSERVABILITY_OFF})
  @DisplayName("with only a web starter, whose executors carry no context")
  class WebStarterOnly {

    @LocalServerPort private int port;

    @Test
    @DisplayName("@Async work sees no limit, as it sees no logging context")
    void asyncSeesNoLimit() throws Exception {
      var answer = WebTestSupport.getFromService(port, "/time-left/async");

      assertThat(answer.statusCode()).isEqualTo(HttpStatus.OK.value());
      assertThat(answer.body()).isEqualTo(Answers.NO_LIMIT);
    }
  }

  @Nested
  @SpringBootTest(
      classes = TimeLeftApplication.class,
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = {
        ROUTE_LIMIT,
        OBSERVABILITY_OFF,
        "spring.task.execution.propagate-context=true",
        "spring.threads.virtual.enabled=true"
      })
  @DisplayName("on virtual threads, with executors that carry the context")
  class VirtualThreads {

    @LocalServerPort private int port;

    @Test
    @DisplayName("the request's thread and its @Async work see the time left")
    void bothSeeTimeLeft() throws Exception {
      var onRequest = WebTestSupport.getFromService(port, "/time-left");
      var inAsync = WebTestSupport.getFromService(port, "/time-left/async");

      assertThat(Long.parseLong(onRequest.body()))
          .isPositive()
          .isLessThanOrEqualTo(ROUTE_LIMIT_MILLIS);
      assertThat(Long.parseLong(inAsync.body()))
          .isPositive()
          .isLessThanOrEqualTo(ROUTE_LIMIT_MILLIS);
    }
  }
}
