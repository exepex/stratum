package com.exepex.stratum.resilience.testsupport;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the test endpoints' work until a test lets it go, and counts how much of it runs at once,
 * so a test can fill a route and see that no more than its limit ran.
 */
public class HeldRequests {

  /** The longest a piece of work is held, so a failed test never hangs the build. */
  private static final Duration HOLD_LIMIT = Duration.ofSeconds(10);

  /** The answer of every held piece of work once it is let go. */
  public static final String DONE = "done";

  /** The work running now. */
  private final AtomicInteger runningNow = new AtomicInteger();

  /** The most work that ran at once since the gate was last closed. */
  private final AtomicInteger mostAtOnce = new AtomicInteger();

  /** Holds the work until it opens. */
  private final AtomicReference<CountDownLatch> gate = new AtomicReference<>(new CountDownLatch(1));

  /** Closes the gate, so new work is held, and forgets the most work that ran at once. */
  public void close() {
    gate.set(new CountDownLatch(1));
    mostAtOnce.set(0);
  }

  /** Opens the gate, so held work and new work end. */
  public void open() {
    gate.get().countDown();
  }

  /**
   * Waits until the gate opens, at most 10 seconds, counting this work while it waits.
   *
   * @return {@link #DONE}
   */
  public String hold() {
    mostAtOnce.accumulateAndGet(runningNow.incrementAndGet(), Math::max);
    try {
      gate.get().await(HOLD_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    } finally {
      runningNow.decrementAndGet();
    }
    return DONE;
  }

  /**
   * Returns the work running now.
   *
   * @return the count
   */
  public int running() {
    return runningNow.get();
  }

  /**
   * Returns the most work that ran at once since the gate was last closed.
   *
   * @return the count
   */
  public int mostRunning() {
    return mostAtOnce.get();
  }
}
