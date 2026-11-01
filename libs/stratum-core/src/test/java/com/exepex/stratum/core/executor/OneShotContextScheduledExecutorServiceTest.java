package com.exepex.stratum.core.executor;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

    assertThat(seenAtFixedRate.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo("null");
    assertThat(seenWithFixedDelay.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo("null");
  }
}
