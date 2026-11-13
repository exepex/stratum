package com.exepex.stratum.data.benchmark;

import com.exepex.stratum.core.request.RequestTimeLeft;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stands in for the web module's time left in the data module's benchmarks, which do not depend on
 * the web module: it reads the deadline of the request on the calling thread as the web module's
 * bean does, from a thread local, an atomic and the monotonic clock. Every thread runs in a request
 * that ends an hour after the thread first asks.
 */
public final class ThreadDeadline implements RequestTimeLeft {

  /** How long each thread's request may run. */
  private static final long HOUR_NANOS = TimeUnit.HOURS.toNanos(1);

  /** The deadline of the request on each thread, in {@link System#nanoTime()} terms. */
  private static final ThreadLocal<AtomicLong> DEADLINE =
      ThreadLocal.withInitial(() -> new AtomicLong(System.nanoTime() + HOUR_NANOS));

  /**
   * Returns the time left of the request on the calling thread.
   *
   * @return the time left, about an hour
   */
  @Override
  public Optional<Duration> timeLeft() {
    return Optional.of(Duration.ofNanos(DEADLINE.get().get() - System.nanoTime()));
  }
}
