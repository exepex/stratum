package com.exepex.stratum.webmvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.web.CurrentRequestTimeLeft;
import com.exepex.stratum.web.RequestDeadline;
import com.exepex.stratum.web.RequestDeadlineAccessor;
import com.exepex.stratum.web.RequestTimedOutException;
import com.exepex.stratum.web.ResponseTimeouts;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebProperties;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("ResponseTimeoutFilter interrupts a request that runs out of time and answers it")
class ResponseTimeoutFilterTest {

  /** How long a test may run before it fails, so a broken filter cannot hang the build. */
  private static final long TEST_TIMEOUT_SECONDS = 5;

  /** How long the test's handlers block, far longer than the limit. */
  private static final long BLOCKED_MILLIS = 400;

  /** The timeouts: 100 milliseconds for every request, answered with 504. */
  private final ResponseTimeouts timeouts =
      new ResponseTimeouts(
          WebProperties.resolve(
              new MockEnvironment()
                  .withProperty(WebConstants.TIMEOUT_DEFAULT_LIMIT, "100ms")
                  .withProperty(WebConstants.TIMEOUT_STATUS, "504")),
          false);

  /** The filter. */
  private final ResponseTimeoutFilter filter =
      new ResponseTimeoutFilter(
          timeouts, new StaticListableBeanFactory().getBeanProvider(ObservationRegistry.class));

  /** Reads the time left of the request on this thread. */
  private final CurrentRequestTimeLeft timeLeft = new CurrentRequestTimeLeft();

  /** The request. */
  private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders/42");

  /** The response. */
  private final MockHttpServletResponse response = new MockHttpServletResponse();

  /** Stops the timer thread. */
  @AfterEach
  void stopTimer() {
    timeouts.close();
  }

  @Test
  @Timeout(TEST_TIMEOUT_SECONDS)
  @DisplayName(
      "a handler blocked past the limit is interrupted, answered with the status, and the"
          + " thread is not left interrupted")
  void blockedHandler() throws Exception {
    filter.doFilter(
        request, response, (blockedRequest, blockedResponse) -> blockUntilInterrupted());

    assertAnsweredWithTimeout();
  }

  @Test
  @Timeout(TEST_TIMEOUT_SECONDS)
  @DisplayName(
      "a later filter that fails with an unchecked exception after the limit is answered with"
          + " the status")
  void lateUncheckedFailure() throws Exception {
    filter.doFilter(
        request, response, (slowRequest, slowResponse) -> failUncheckedOnceInterrupted());

    assertAnsweredWithTimeout();
  }

  @Test
  @DisplayName("a later filter that fails with an unchecked exception in time keeps its failure")
  void uncheckedFailureInTime() {
    assertThatThrownBy(
            () ->
                filter.doFilter(
                    request,
                    response,
                    (failedRequest, failedResponse) -> {
                      throw new IllegalStateException("the token store is down");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("the token store is down");
  }

  @Test
  @DisplayName("a handler that fails in time keeps its failure")
  void failureInTime() {
    assertThatThrownBy(
            () ->
                filter.doFilter(
                    request,
                    response,
                    (failedRequest, failedResponse) -> {
                      throw new ServletException("the order store is down");
                    }))
        .hasMessage("the order store is down");
  }

  @Test
  @DisplayName("a response that has started is not interrupted")
  void startedResponse() throws Exception {
    filter.doFilter(
        request,
        response,
        (streamingRequest, streamingResponse) -> {
          streamingResponse.flushBuffer();
          assertThat(blockWithoutInterrupt()).isFalse();
        });

    assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    assertThat(Thread.currentThread().isInterrupted()).isFalse();
  }

  @Test
  @DisplayName(
      "the request's deadline is on its thread while it runs, and gone from the thread after it,"
          + " also after a failure")
  void deadlineOnThreadOnlyWhileRunning() throws Exception {
    var seen = new ArrayList<Optional<Duration>>();

    filter.doFilter(request, response, (inTime, itsResponse) -> seen.add(timeLeft.timeLeft()));
    assertThatThrownBy(
            () ->
                filter.doFilter(
                    request,
                    response,
                    (failedRequest, failedResponse) -> {
                      throw new ServletException("the order store is down");
                    }))
        .isInstanceOf(ServletException.class);

    assertThat(seen).singleElement().satisfies(left -> assertThat(left).isPresent());
    assertThat(new RequestDeadlineAccessor().getValue()).isNull();
  }

  @Test
  @DisplayName(
      "a handler that started its response itself still reads its time left before the limit")
  void startedResponseKeepsTimeLeft() throws Exception {
    var seen = new ArrayList<Optional<Duration>>();

    filter.doFilter(
        request,
        response,
        (streamingRequest, streamingResponse) -> seen.add(timeLeftAfterFlush(streamingResponse)));

    assertThat(seen).singleElement().satisfies(left -> assertThat(left).isPresent());
  }

  @Test
  @Timeout(TEST_TIMEOUT_SECONDS)
  @DisplayName(
      "a handler that started its response reads no limit once the limit has passed, and is not"
          + " interrupted")
  void startedResponseHasNoLimitPastIt() throws Exception {
    var seen = new ArrayList<Optional<Duration>>();

    filter.doFilter(
        request,
        response,
        (streamingRequest, streamingResponse) ->
            seen.add(timeLeftPastLimitOfStarted(streamingResponse)));

    assertThat(seen).containsExactly(Optional.empty());
    assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    assertThat(Thread.currentThread().isInterrupted()).isFalse();
  }

  @Test
  @Timeout(TEST_TIMEOUT_SECONDS)
  @DisplayName("a handler whose response has not started still reads zero or less past the limit")
  void unstartedResponseKeepsPassedLimit() throws Exception {
    var seen = new ArrayList<Optional<Duration>>();

    filter.doFilter(
        request, response, (slowRequest, slowResponse) -> seen.add(timeLeftAfterInterrupt()));

    assertThat(seen)
        .singleElement()
        .satisfies(
            left ->
                assertThat(left)
                    .hasValueSatisfying(passed -> assertThat(passed.isPositive()).isFalse()));
  }

  /**
   * Starts the response, runs past the limit without being interrupted, and returns the time left
   * then.
   */
  private Optional<Duration> timeLeftPastLimitOfStarted(ServletResponse streamingResponse)
      throws IOException, ServletException {
    streamingResponse.flushBuffer();
    assertThat(blockWithoutInterrupt()).isFalse();
    return timeLeft.timeLeft();
  }

  /** Waits for the filter's interrupt, then returns the time left. */
  private Optional<Duration> timeLeftAfterInterrupt() {
    awaitInterrupt();
    return timeLeft.timeLeft();
  }

  /** Waits for the filter's interrupt, and keeps the thread's interrupted state. */
  private void awaitInterrupt() {
    try {
      new CountDownLatch(1).await(BLOCKED_MILLIS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  @DisplayName(
      "a limit that runs out before the filter has the deadline interrupts the request, and the"
          + " request still runs")
  void expiryBeforeDeadlineIsKnown() throws Exception {
    var interrupted = new ArrayList<Boolean>();
    try (var expiringAtStart = new ExpiringAtStart()) {
      var expiringFilter =
          new ResponseTimeoutFilter(
              expiringAtStart,
              new StaticListableBeanFactory().getBeanProvider(ObservationRegistry.class));

      expiringFilter.doFilter(
          request,
          response,
          (earlyRequest, earlyResponse) -> interrupted.add(Thread.interrupted()));
    }

    assertThat(interrupted).containsExactly(true);
    assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
  }

  /**
   * Time limits whose action runs before {@code start} returns the deadline, as the timer can when
   * the limit has already run out: the filter's callback then has no deadline yet.
   */
  private static final class ExpiringAtStart extends ResponseTimeouts {

    /** Builds the limits of the other tests. */
    ExpiringAtStart() {
      super(
          WebProperties.resolve(
              new MockEnvironment().withProperty(WebConstants.TIMEOUT_DEFAULT_LIMIT, "100ms")),
          false);
    }

    @Override
    public RequestDeadline start(String pathWithinApplication, Runnable onExpiry) {
      onExpiry.run();
      return super.start(pathWithinApplication, onExpiry);
    }
  }

  /**
   * Checks that the request was answered with the timeout status, through the timeout, and that the
   * thread is not left interrupted.
   */
  private void assertAnsweredWithTimeout() {
    assertThat(response.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT.value());
    assertThat(request.getAttribute(ResponseTimeoutFilter.TIMED_OUT_ATTRIBUTE))
        .isInstanceOf(RequestTimedOutException.class);
    assertThat(Thread.currentThread().isInterrupted()).isFalse();
  }

  /**
   * Blocks as a slow security check does, and fails with an unchecked exception once interrupted,
   * as a filter that is not a Spring MVC handler does.
   */
  private void failUncheckedOnceInterrupted() {
    try {
      new CountDownLatch(1).await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("the token lookup was interrupted", interrupted);
    }
  }

  /**
   * Blocks as a slow call that honours interrupts does, and fails once interrupted, keeping the
   * thread's interrupt as well-behaved code does.
   */
  private void blockUntilInterrupted() throws ServletException {
    try {
      new CountDownLatch(1).await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new ServletException("the slow call was interrupted", interrupted);
    }
  }

  /** Starts the response, then returns the time left. */
  private Optional<Duration> timeLeftAfterFlush(ServletResponse streamingResponse)
      throws IOException {
    streamingResponse.flushBuffer();
    return timeLeft.timeLeft();
  }

  /** Blocks for longer than the limit; returns whether the wait was cut short. */
  private boolean blockWithoutInterrupt() throws ServletException {
    try {
      return new CountDownLatch(1).await(BLOCKED_MILLIS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      throw new ServletException("the started response was interrupted", interrupted);
    }
  }
}
