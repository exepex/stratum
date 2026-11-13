package com.exepex.stratum.web;

import com.exepex.stratum.core.request.RequestTimeLeft;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Synchronized;
import org.jspecify.annotations.Nullable;

/**
 * The time limit of one request. When the request runs out of time before it ends, the action it
 * was started with runs once, on the timer's thread. The limit can change while the request runs,
 * as when its handler sets its own; it always counts from the request's start. Its time left is
 * read without a lock, so work the request hands to other threads sees the limit as it is now.
 * {@link ResponseTimeouts} makes it.
 */
public final class RequestDeadline implements RequestTimeLeft {

  /** The longest limit counted in nanoseconds; a longer one is treated as this one. */
  private static final Duration LONGEST_LIMIT = Duration.ofNanos(Long.MAX_VALUE);

  /** When the request started, from {@link System#nanoTime()}. */
  private final long startNanos = System.nanoTime();

  /** How long the request may take; zero or less for no limit. */
  private Duration currentLimit = Duration.ZERO;

  /**
   * The limit in nanoseconds, read without a lock; 0 for no limit, and 0 once the request ended.
   */
  private final AtomicLong limitNanos = new AtomicLong();

  /**
   * The timer and the action, empty once the request ended, so work still holding the deadline does
   * not keep the request's response; the action never runs after that.
   */
  private final AtomicReference<@Nullable Expiry> expiry;

  /** The pending run of the action, if the request has had a limit. */
  private @Nullable ScheduledFuture<?> pendingExpiry;

  /** Whether the request ran out of time. */
  private boolean expired;

  /**
   * Makes a deadline with no limit yet.
   *
   * @param timer runs the action when the time is up
   * @param onExpiry what happens when the request runs out of time, such as interrupting its thread
   */
  RequestDeadline(ScheduledExecutorService timer, Runnable onExpiry) {
    this.expiry = new AtomicReference<>(new Expiry(timer, onExpiry));
  }

  /**
   * Sets how long the request may take, counted from its start, in place of the limit it had. It
   * changes nothing once the request ran out of time or ended.
   *
   * @param newLimit the limit; zero or less for no limit
   */
  @Synchronized
  public void limitTo(Duration newLimit) {
    var current = expiry.get();
    if (expired || current == null) {
      return;
    }
    cancelPendingExpiry();
    currentLimit = newLimit;
    var nanos = nanosOf(newLimit);
    limitNanos.set(nanos);
    if (nanos > 0) {
      pendingExpiry =
          current
              .timer()
              .schedule(this::expire, Math.max(nanosLeft(nanos), 0), TimeUnit.NANOSECONDS);
    }
  }

  /**
   * Returns how long the request may take.
   *
   * @return the limit; zero or less for none
   */
  @Synchronized
  public Duration limit() {
    return currentLimit;
  }

  /**
   * Returns how long the request may still run, without taking a lock.
   *
   * @return the time left, zero or negative once the limit has passed; empty when the request has
   *     no limit or has ended
   */
  @Override
  public Optional<Duration> timeLeft() {
    var limit = limitNanos.get();
    return limit == 0 ? Optional.empty() : Optional.of(Duration.ofNanos(nanosLeft(limit)));
  }

  /**
   * Returns whether the request ran out of time.
   *
   * @return whether the action ran
   */
  @Synchronized
  public boolean hasExpired() {
    return expired;
  }

  /**
   * Ends the request's time limit: the action never runs after this returns, and the time left is
   * reported as no limit. Call it on the request's own thread when the request ends.
   */
  @Synchronized
  public void end() {
    limitNanos.set(0);
    cancelPendingExpiry();
    expiry.set(null);
  }

  /**
   * Runs the action, unless the request ended first or its limit has since grown or gone: a run
   * scheduled for an earlier limit can already have started when {@link #limitTo} cancels it.
   */
  @Synchronized
  private void expire() {
    var current = expiry.get();
    var limit = limitNanos.get();
    if (current != null && limit > 0 && nanosLeft(limit) <= 0) {
      expired = true;
      current.onExpiry().run();
    }
  }

  /** Returns a limit in nanoseconds: 0 for none, and at most the largest long. */
  private long nanosOf(Duration newLimit) {
    if (!newLimit.isPositive()) {
      return 0;
    }
    return newLimit.compareTo(LONGEST_LIMIT) < 0 ? newLimit.toNanos() : Long.MAX_VALUE;
  }

  /** Returns the nanoseconds left before a limit, counted from the request's start. */
  private long nanosLeft(long limit) {
    return limit - (System.nanoTime() - startNanos);
  }

  /** Cancels the pending run of the action, if any; cancelling a finished run does nothing. */
  private void cancelPendingExpiry() {
    if (pendingExpiry != null) {
      pendingExpiry.cancel(false);
    }
  }

  /**
   * The timer that runs the action, and the action.
   *
   * @param timer runs the action when the time is up
   * @param onExpiry what happens when the request runs out of time
   */
  private record Expiry(ScheduledExecutorService timer, Runnable onExpiry) {}
}
