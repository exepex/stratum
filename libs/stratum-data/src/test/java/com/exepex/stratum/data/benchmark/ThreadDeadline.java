package com.exepex.stratum.data.benchmark;

import com.exepex.stratum.core.request.RequestTimeLeft;
import java.sql.SQLException;
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

  /** The deadline margin Stratum takes off the request's time left, by default. */
  private static final long MARGIN_MILLIS = 150;

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

  /**
   * Returns the request's time left minus the margin, in milliseconds, as Stratum reads it.
   *
   * @return the time left for the database
   */
  public long millisLeft() {
    return timeLeft().map(left -> left.toMillis() - MARGIN_MILLIS).orElse(Long.MAX_VALUE);
  }

  /**
   * Fails when the request has no time left, as Stratum does before and after a borrow.
   *
   * @throws SQLException when no time is left, which never happens here
   */
  public void checkTimeLeft() throws SQLException {
    if (millisLeft() <= 0) {
      throw new SQLException("No time is left");
    }
  }
}
