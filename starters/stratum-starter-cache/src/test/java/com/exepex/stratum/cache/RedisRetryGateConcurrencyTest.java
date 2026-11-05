package com.exepex.stratum.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.cache.testsupport.AdjustableClock;
import com.exepex.stratum.cache.testsupport.RecordingCacheCounters;
import com.exepex.stratum.cache.testsupport.RedisFailureTestData;
import com.exepex.stratum.cache.testsupport.RedisRetryGates;
import java.time.Instant;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RedisRetryGate counts failures in a row correctly when calls end at the same time")
class RedisRetryGateConcurrencyTest {

  /** How many failures the first test reports at the same time. */
  private static final int CONCURRENT_FAILURES = 1_000;

  /** The default threshold, which the race test uses. */
  private static final int FAILURE_THRESHOLD = 3;

  /** How many times the race test lets a success and a failure end together. */
  private static final int RACE_ROUNDS = 100_000;

  /** How long one side of a race waits for the other, in seconds. */
  private static final long BARRIER_WAIT_SECONDS = 5;

  private final AdjustableClock clock = new AdjustableClock(Instant.parse("2026-11-04T08:00:00Z"));

  private final RecordingCacheCounters counters = new RecordingCacheCounters();

  @Test
  @DisplayName("failures at the same time each count, so the next one reaches the threshold")
  void concurrentFailuresAllCount() {
    var gate = reachedGate(CONCURRENT_FAILURES + 1);

    IntStream.range(0, CONCURRENT_FAILURES)
        .parallel()
        .forEach(attempt -> gate.failed(RedisFailureTestData.TIMED_OUT, true));

    assertThat(gate.usesRedis()).isTrue();
    gate.failed(RedisFailureTestData.TIMED_OUT, true);
    assertThat(gate.usesRedis()).isFalse();
  }

  @Test
  @DisplayName(
      "a success at the same time as a failure keeps its reset, so one more failure keeps Redis")
  void successRacingFailureKeepsReset() {
    var gate = reachedGate(FAILURE_THRESHOLD);

    try (var executor = Executors.newFixedThreadPool(2)) {
      IntStream.range(0, RACE_ROUNDS).forEach(round -> raceSuccessAgainstFailure(gate, executor));
    }
  }

  /** Returns a gate whose first call reached Redis, so every call uses it. */
  private RedisRetryGate reachedGate(int failureThreshold) {
    var gate = RedisRetryGates.gate(failureThreshold, clock, counters);
    assertThat(gate.admits()).isTrue();
    gate.reached();
    return gate;
  }

  /**
   * From one counted failure, lets a success and a failure end at the same time. Either order
   * leaves at most one failure, so one more failure still stays below the threshold of 3.
   */
  private void raceSuccessAgainstFailure(RedisRetryGate gate, ExecutorService executor) {
    gate.failed(RedisFailureTestData.TIMED_OUT, true);
    var barrier = new CyclicBarrier(2);

    CompletableFuture.allOf(
            CompletableFuture.runAsync(() -> afterBarrier(barrier, gate::reached), executor),
            CompletableFuture.runAsync(
                () ->
                    afterBarrier(barrier, () -> gate.failed(RedisFailureTestData.TIMED_OUT, true)),
                executor))
        .join();
    gate.failed(RedisFailureTestData.TIMED_OUT, true);

    assertThat(gate.usesRedis()).isTrue();
    gate.reached();
  }

  /** Waits until the other side of the race is ready, then runs this side's call. */
  private void afterBarrier(CyclicBarrier barrier, Runnable call) {
    try {
      barrier.await(BARRIER_WAIT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    } catch (BrokenBarrierException | TimeoutException notReady) {
      throw new IllegalStateException(notReady);
    }
    call.run();
  }
}
