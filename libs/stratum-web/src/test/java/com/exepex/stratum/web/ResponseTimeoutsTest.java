package com.exepex.stratum.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.web.testsupport.WebTestData;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;

@DisplayName("ResponseTimeouts gives each request its limit and acts when it runs out")
class ResponseTimeoutsTest {

  /** The path of the daily reports, which have a short limit. */
  private static final String DAILY_REPORTS = "/reports/daily";

  /** The path of an order, which matches no route. */
  private static final String ORDER = "/orders/42";

  /** The limit of a request whose path matches no route. */
  private static final Duration DEFAULT_LIMIT = Duration.ofSeconds(10);

  /** The limit of the slow report's handler. */
  private static final Duration SLOW_REPORT_LIMIT = Duration.ofMillis(1500);

  /** How long a test waits for an action to run. */
  private static final Duration WAIT = Duration.ofSeconds(5);

  /** A short limit, which a test waits out. */
  private static final Duration SHORT = Duration.ofMillis(50);

  /** Longer than any test runs. */
  private static final Duration LONG = Duration.ofSeconds(30);

  /** How long a test waits to see that an action did not run. */
  private static final Duration QUIET_PERIOD = Duration.ofMillis(300);

  /** The timeouts: a 10 second default, daily reports with a short limit, other reports none. */
  private final ResponseTimeouts timeouts =
      new ResponseTimeouts(
          WebTestData.defaults()
              .timeout(
                  new WebProperties.TimeoutSettings(
                      DEFAULT_LIMIT,
                      Map.of("/reports/**", Duration.ZERO, DAILY_REPORTS, SHORT),
                      HttpStatus.GATEWAY_TIMEOUT.value()))
              .build());

  /** Whether the action of the request under test ran. */
  private final AtomicBoolean expired = new AtomicBoolean();

  /** Stops the timer thread. */
  @AfterEach
  void stopTimer() {
    timeouts.close();
  }

  @Nested
  @DisplayName("the limit of a request")
  class Limits {

    @Test
    @DisplayName("is the default when no route matches")
    void defaultLimit() {
      var deadline = timeouts.start(ORDER, () -> expired.set(true));
      assertThat(deadline.limit()).isEqualTo(DEFAULT_LIMIT);
    }

    @Test
    @DisplayName("is the most specific route's, and 0 means no limit")
    void mostSpecificRoute() {
      var daily = timeouts.start(DAILY_REPORTS, () -> expired.set(true));
      var monthly = timeouts.start("/reports/monthly", () -> expired.set(true));
      assertThat(daily.limit()).isEqualTo(SHORT);
      assertThat(monthly.limit()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("is the handler's @ResponseTimeout, when it has one")
    void handlerLimitWins() throws NoSuchMethodException {
      // The default, not the short daily limit, which a loaded build can run out before the end.
      var deadline = timeouts.start(ORDER, () -> expired.set(true));
      timeouts.applyHandlerLimit(deadline, handler("plainReport"));
      timeouts.applyHandlerLimit(deadline, "a handler that is not a method");
      assertThat(deadline.limit()).isEqualTo(DEFAULT_LIMIT);

      timeouts.applyHandlerLimit(deadline, handler("slowReport"));
      assertThat(deadline.limit()).isEqualTo(SLOW_REPORT_LIMIT);
    }
  }

  @Nested
  @DisplayName("the action of a request")
  class Expiry {

    @Test
    @DisplayName("runs once the limit has passed")
    void runsWhenTimeIsUp() {
      var deadline = timeouts.start(DAILY_REPORTS, () -> expired.set(true));
      await().atMost(WAIT).untilTrue(expired);
      assertThat(deadline.hasExpired()).isTrue();
    }

    @Test
    @DisplayName("never runs once the request has ended")
    void neverRunsAfterEnd() {
      var deadline = timeouts.start(DAILY_REPORTS, () -> expired.set(true));
      deadline.end();

      await().during(QUIET_PERIOD).atMost(QUIET_PERIOD.multipliedBy(2)).untilFalse(expired);
      assertThat(deadline.hasExpired()).isFalse();
    }

    @Test
    @DisplayName("waits for a longer limit set while the request runs")
    void longerLimitDelaysIt() {
      var deadline = timeouts.start(ORDER, () -> expired.set(true));
      deadline.limitTo(SLOW_REPORT_LIMIT);
      deadline.limitTo(LONG);

      await().during(SLOW_REPORT_LIMIT.multipliedBy(2)).atMost(WAIT).untilFalse(expired);
      assertThat(deadline.hasExpired()).isFalse();
    }
  }

  @Test
  @DisplayName(
      "a request that ran out is answered with the timeout status and logged with its limit")
  @ExtendWith(OutputCaptureExtension.class)
  void timedOut(CapturedOutput output) {
    var deadline = timeouts.start(DAILY_REPORTS, () -> expired.set(true));
    var failure = timeouts.timedOut(deadline);

    assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    assertThat(output).contains("WARN", "The request ran out of time", "PT0.05S");
  }

  /** Returns the handler method of {@link ReportController} with the name. */
  private HandlerMethod handler(String methodName) throws NoSuchMethodException {
    return new HandlerMethod(new ReportController(), methodName);
  }

  /** A controller with one API that has its own limit. */
  public static class ReportController {

    /**
     * Has no limit of its own.
     *
     * @return the report
     */
    public String plainReport() {
      return "plain";
    }

    /**
     * Has 1.5 seconds.
     *
     * @return the report
     */
    @ResponseTimeout(value = 1500, unit = TimeUnit.MILLISECONDS)
    public String slowReport() {
      return "slow";
    }
  }
}
