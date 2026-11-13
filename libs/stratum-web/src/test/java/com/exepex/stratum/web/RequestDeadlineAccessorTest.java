package com.exepex.stratum.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("A request's deadline is on its threads, and only there, for its time left")
class RequestDeadlineAccessorTest {

  /** A limit no test outlives. */
  private static final Duration LONG = Duration.ofSeconds(30);

  /** A longer limit, set while the request runs. */
  private static final Duration LONGER = Duration.ofSeconds(60);

  /** How long a test waits for work on another thread. */
  private static final long WAIT_SECONDS = 5;

  /** Takes the context of the submitting thread, as Spring's executors do. */
  private static final ContextSnapshotFactory SNAPSHOTS = ContextSnapshotFactory.builder().build();

  /** Puts and reads the deadline. */
  private final RequestDeadlineAccessor deadlines = new RequestDeadlineAccessor();

  /** The time left of the current request, as the bean reads it. */
  private final CurrentRequestTimeLeft timeLeft = new CurrentRequestTimeLeft();

  /** Runs the action of each deadline. */
  private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);

  /** Registers the accessor, as the web module does when it starts. */
  @BeforeAll
  static void registerAccessor() {
    new RequestDeadlinePropagation().afterSingletonsInstantiated();
  }

  /** Leaves the thread with no deadline, and stops the timer. */
  @AfterEach
  void clearThread() {
    deadlines.setValue();
    timer.shutdownNow();
  }

  /** Returns a deadline with a 30 second limit. */
  private RequestDeadline limitedDeadline() {
    var deadline = new RequestDeadline(timer, () -> {});
    deadline.limitTo(LONG);
    return deadline;
  }

  @Nested
  @DisplayName("on the request's own thread")
  class OwnThread {

    @Test
    @DisplayName("there is no limit outside a request's work")
    void noLimitOutsideRequest() {
      assertThat(deadlines.getValue()).isNull();
      assertThat(timeLeft.timeLeft()).isEmpty();
    }

    @Test
    @DisplayName("the time left is the deadline's, for every reader in the JVM")
    void readsDeadlineOnThread() {
      deadlines.setValue(limitedDeadline());

      assertThat(timeLeft.timeLeft()).hasValueSatisfying(left -> assertThat(left).isPositive());
      assertThat(new CurrentRequestTimeLeft().timeLeft()).isPresent();
      assertThat(new RequestDeadlineAccessor().getValue()).isSameAs(deadlines.getValue());
    }

    @Test
    @DisplayName("there is no limit once the deadline is removed")
    void noLimitOnceRemoved() {
      deadlines.setValue(limitedDeadline());

      deadlines.setValue();

      assertThat(timeLeft.timeLeft()).isEmpty();
    }

    @Test
    @DisplayName("is under the key the web module carries it with")
    void key() {
      assertThat(deadlines.key()).isEqualTo(WebConstants.REQUEST_DEADLINE_CONTEXT_KEY);
      assertThat(ContextRegistry.getInstance().getThreadLocalAccessors())
          .anyMatch(accessor -> WebConstants.REQUEST_DEADLINE_CONTEXT_KEY.equals(accessor.key()));
    }
  }

  @Nested
  @DisplayName("on other threads")
  class OtherThreads {

    @Test
    @DisplayName("a thread the deadline was not carried to sees no limit")
    void notCarriedMeansNoLimit() throws Exception {
      deadlines.setValue(limitedDeadline());
      try (var pool = Executors.newSingleThreadExecutor()) {
        var seen = CompletableFuture.supplyAsync(timeLeft::timeLeft, pool);

        assertThat(seen.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEmpty();
      }
    }

    @Test
    @DisplayName(
        "a task the context was carried to sees the deadline itself, with a limit changed after"
            + " it was submitted, and leaves its thread with none")
    void carriedLive() throws Exception {
      var deadline = limitedDeadline();
      deadlines.setValue(deadline);
      try (var pool = Executors.newSingleThreadExecutor()) {
        var task = SNAPSHOTS.captureAll().wrap(timeLeft::timeLeft);
        deadline.limitTo(LONGER);

        var seen = pool.submit(task).get(WAIT_SECONDS, TimeUnit.SECONDS);
        var afterTask = pool.submit(timeLeft::timeLeft).get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(seen).hasValueSatisfying(left -> assertThat(left).isGreaterThan(LONG));
        assertThat(afterTask).isEmpty();
      }
    }
  }
}
