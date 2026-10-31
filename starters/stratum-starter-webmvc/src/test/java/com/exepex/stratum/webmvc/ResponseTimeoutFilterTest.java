package com.exepex.stratum.webmvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.web.RequestTimedOutException;
import com.exepex.stratum.web.ResponseTimeouts;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebProperties;
import jakarta.servlet.ServletException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
                  .withProperty(WebConstants.TIMEOUT_STATUS, "504")));

  /** The filter. */
  private final ResponseTimeoutFilter filter = new ResponseTimeoutFilter(timeouts);

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

    assertThat(response.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT.value());
    assertThat(request.getAttribute(ResponseTimeoutFilter.TIMED_OUT_ATTRIBUTE))
        .isInstanceOf(RequestTimedOutException.class);
    assertThat(Thread.currentThread().isInterrupted()).isFalse();
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

  /** Blocks for longer than the limit; returns whether the wait was cut short. */
  private boolean blockWithoutInterrupt() throws ServletException {
    try {
      return new CountDownLatch(1).await(BLOCKED_MILLIS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      throw new ServletException("the started response was interrupted", interrupted);
    }
  }
}
