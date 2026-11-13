package com.exepex.stratum.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

@DisplayName("RequestDeadline tells how long its request has left, live and without a lock")
class RequestDeadlineTimeLeftTest {

  /** A limit no test outlives. */
  private static final Duration LONG = Duration.ofSeconds(30);

  /** A limit a test waits out. */
  private static final Duration SHORT = Duration.ofMillis(50);

  /** A limit a test replaces with a longer one while the request runs. */
  private static final Duration FIRST_LIMIT = Duration.ofSeconds(5);

  /** A limit too long to count in nanoseconds, which can only come from a mistake. */
  private static final Duration HUGE = Duration.ofDays(200_000);

  /** Shorter than the longest limit counted in nanoseconds, about 292 years. */
  private static final Duration CENTURIES = Duration.ofDays(100_000);

  /**
   * The deadline's field that holds its action. Read directly, because whether an object can still
   * be reached is not otherwise visible without forcing a garbage collection.
   */
  private static final String EXPIRY_FIELD = "expiry";

  /** How long a test waits for something to happen. */
  private static final Duration WAIT = Duration.ofSeconds(5);

  /** Runs the action of each deadline. */
  private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);

  /** Whether the action of the deadline under test ran. */
  private final AtomicBoolean expired = new AtomicBoolean();

  /** Stops the timer. */
  @AfterEach
  void stopTimer() {
    timer.shutdownNow();
  }

  /** Returns a deadline whose action records that it ran. */
  private RequestDeadline newDeadline() {
    return new RequestDeadline(timer, () -> expired.set(true));
  }

  @Nested
  @DisplayName("with no limit")
  class NoLimit {

    /** Limits that mean no limit: zero and less. */
    private static final List<Duration> NO_LIMITS = List.of(Duration.ZERO, Duration.ofSeconds(-1));

    @Test
    @DisplayName("reports no limit before any limit is set")
    void noLimitBeforeAnyIsSet() {
      assertThat(newDeadline().timeLeft()).isEmpty();
    }

    @ParameterizedTest
    @FieldSource("NO_LIMITS")
    @DisplayName("reports no limit once a limit of 0 or less replaces a limit")
    void zeroMeansNoLimit(Duration noLimit) {
      var deadline = newDeadline();
      deadline.limitTo(LONG);

      deadline.limitTo(noLimit);

      assertThat(deadline.timeLeft()).isEmpty();
    }
  }

  @Nested
  @DisplayName("with a limit")
  class WithLimit {

    @Test
    @DisplayName("reports the limit minus the time the request has run")
    void limitMinusElapsed() {
      var deadline = newDeadline();
      deadline.limitTo(LONG);

      assertThat(deadline.timeLeft())
          .hasValueSatisfying(left -> assertThat(left).isPositive().isLessThanOrEqualTo(LONG));
    }

    @Test
    @DisplayName("shows another thread a limit set after that thread got the deadline")
    void anotherThreadSeesNewLimit() throws Exception {
      var deadline = newDeadline();
      deadline.limitTo(FIRST_LIMIT);
      var limitChanged = new CountDownLatch(1);
      try (var otherThread = Executors.newSingleThreadExecutor()) {
        var seen =
            CompletableFuture.supplyAsync(() -> timeLeftAfter(limitChanged, deadline), otherThread);

        deadline.limitTo(LONG);
        limitChanged.countDown();

        assertThat(seen.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isGreaterThan(FIRST_LIMIT);
      }
    }

    @Test
    @DisplayName("takes a limit too long to count in nanoseconds as the longest it can count")
    void hugeLimitSaturates() {
      var deadline = newDeadline();

      deadline.limitTo(HUGE);

      assertThat(deadline.timeLeft())
          .hasValueSatisfying(left -> assertThat(left).isGreaterThan(CENTURIES));
    }

    @Test
    @DisplayName("reports zero or less once the limit has passed, until the request ends")
    void passedLimit() {
      var deadline = newDeadline();
      deadline.limitTo(SHORT);
      await().atMost(WAIT).untilTrue(expired);

      assertThat(deadline.timeLeft())
          .hasValueSatisfying(left -> assertThat(left.isPositive()).isFalse());
    }
  }

  @Nested
  @DisplayName("once the request has ended")
  class AfterEnd {

    @Test
    @DisplayName("reports no limit, whether or not the limit had passed")
    void noLimitAfterEnd() {
      var inTime = newDeadline();
      inTime.limitTo(LONG);
      var late = newDeadline();
      late.limitTo(SHORT);
      await().atMost(WAIT).untilTrue(expired);

      inTime.end();
      late.end();

      assertThat(inTime.timeLeft()).isEmpty();
      assertThat(late.timeLeft()).isEmpty();
    }

    @Test
    @DisplayName("ignores a later limit")
    void ignoresLaterLimit() {
      var deadline = newDeadline();
      deadline.end();

      deadline.limitTo(LONG);

      assertThat(deadline.timeLeft()).isEmpty();
    }

    @Test
    @DisplayName("lets go of its action, so work still holding it does not keep the response")
    void dropsAction() {
      var deadline = newDeadline();
      deadline.limitTo(LONG);
      assertThat(deadline)
          .as("held while the request runs")
          .extracting(EXPIRY_FIELD, InstanceOfAssertFactories.ATOMIC_REFERENCE)
          .doesNotHaveNullValue();

      deadline.end();

      assertThat(deadline)
          .extracting(EXPIRY_FIELD, InstanceOfAssertFactories.ATOMIC_REFERENCE)
          .hasNullValue();
    }
  }

  /** Waits for the signal, then returns the deadline's time left. */
  private Duration timeLeftAfter(CountDownLatch signal, RequestDeadline deadline) {
    try {
      signal.await(WAIT.toSeconds(), TimeUnit.SECONDS);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
    return deadline.timeLeft().orElseThrow();
  }
}
