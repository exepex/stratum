package com.exepex.stratum.core.executor;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName(
    "OneShotContextScheduledExecutorService gives the caller's context to one-shot tasks only")
class OneShotContextScheduledExecutorServiceTest {

  /** The MDC field the caller's thread holds. */
  private static final String FIELD = "customerId";

  /** The value the caller's thread holds. */
  private static final String CALLER_VALUE = "from-caller";

  /** What {@code String.valueOf} shows for a field that is not set. */
  private static final String NOT_SET = "null";

  /** The run of a repeating task that checks what the run before it left behind. */
  private static final int SECOND_RUN = 2;

  /** An MDC field a repeating task puts and forgets to remove. */
  private static final String FORGOTTEN_FIELD = "batchId";

  /** How long a test waits for a task, so a broken pool fails the test instead of hanging. */
  private static final long WAIT_SECONDS = 5;

  private final OneShotContextScheduledExecutorService pool =
      new OneShotContextScheduledExecutorService(Executors.newSingleThreadScheduledExecutor());

  /** Lets context snapshots carry the MDC, as the logging module does in a service. */
  @BeforeAll
  static void carryMdc() {
    ContextRegistry.getInstance().registerThreadLocalAccessor(new Slf4jThreadLocalAccessor());
  }

  /** Stops carrying the MDC, so no other test sees it carried. */
  @AfterAll
  static void stopCarryingMdc() {
    ContextRegistry.getInstance().removeThreadLocalAccessor(Slf4jThreadLocalAccessor.KEY);
  }

  /** Stops the pool and clears the caller's value. */
  @AfterEach
  void stop() {
    pool.shutdownNow();
    MDC.clear();
  }

  @Test
  @DisplayName("a task that runs once, Runnable or Callable, sees the caller's value")
  void oneShotTasksCarryContext() throws Exception {
    MDC.put(FIELD, CALLER_VALUE);
    var seenByRunnable = new CompletableFuture<String>();

    pool.schedule(() -> seenByRunnable.complete(MDC.get(FIELD)), 1, TimeUnit.MILLISECONDS);
    var seenByCallable =
        pool.schedule(() -> MDC.get(FIELD), 1, TimeUnit.MILLISECONDS)
            .get(WAIT_SECONDS, TimeUnit.SECONDS);

    assertThat(seenByRunnable.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(CALLER_VALUE);
    assertThat(seenByCallable).isEqualTo(CALLER_VALUE);
  }

  @Test
  @DisplayName("a repeating task, at a fixed rate or with a fixed delay, runs without it")
  void repeatingTasksRunWithoutContext() throws Exception {
    MDC.put(FIELD, CALLER_VALUE);
    var seenAtFixedRate = new CompletableFuture<String>();
    var seenWithFixedDelay = new CompletableFuture<String>();

    pool.scheduleAtFixedRate(
        () -> seenAtFixedRate.complete(String.valueOf(MDC.get(FIELD))),
        1,
        1,
        TimeUnit.MILLISECONDS);
    pool.scheduleWithFixedDelay(
        () -> seenWithFixedDelay.complete(String.valueOf(MDC.get(FIELD))),
        1,
        1,
        TimeUnit.MILLISECONDS);

    assertThat(seenAtFixedRate.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(NOT_SET);
    assertThat(seenWithFixedDelay.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(NOT_SET);
  }

  @Test
  @DisplayName("a field a repeating task forgets to remove never reaches its next run")
  void repeatingTaskLeavesNoFieldBehind() throws Exception {
    var seenAtFixedRate = secondRunSees(pool::scheduleAtFixedRate);
    var seenWithFixedDelay = secondRunSees(pool::scheduleWithFixedDelay);

    assertThat(seenAtFixedRate).isEqualTo(NOT_SET);
    assertThat(seenWithFixedDelay).isEqualTo(NOT_SET);
  }

  /**
   * Schedules a repeating task that puts a field and never removes it, and returns what its second
   * run sees of that field.
   */
  private String secondRunSees(RepeatingScheduler scheduler) throws Exception {
    var runs = new AtomicInteger();
    var seenBySecondRun = new CompletableFuture<String>();
    var repeating =
        scheduler.schedule(
            () -> {
              if (runs.incrementAndGet() == SECOND_RUN) {
                seenBySecondRun.complete(String.valueOf(MDC.get(FORGOTTEN_FIELD)));
              }
              MDC.put(FORGOTTEN_FIELD, "left-behind");
            },
            1,
            1,
            TimeUnit.MILLISECONDS);
    try {
      return seenBySecondRun.get(WAIT_SECONDS, TimeUnit.SECONDS);
    } finally {
      repeating.cancel(true);
    }
  }

  /** Either of the pool's two ways to schedule a repeating task. */
  @FunctionalInterface
  private interface RepeatingScheduler {

    /**
     * Schedules the task to repeat.
     *
     * @param command the task
     * @param initialDelay how long to wait before the first run
     * @param interval the period or the delay between runs
     * @param unit the unit of both
     * @return the task's future
     */
    ScheduledFuture<?> schedule(Runnable command, long initialDelay, long interval, TimeUnit unit);
  }
}
