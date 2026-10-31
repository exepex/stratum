package com.exepex.stratum.web;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Synchronized;
import org.jspecify.annotations.Nullable;

/**
 * The time limit of one request. When the request runs out of time before it ends, the action it
 * was started with runs once, on the timer's thread. The limit can change while the request runs,
 * as when its handler sets its own; it always counts from the request's start. {@link
 * ResponseTimeouts} makes it.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class RequestDeadline {

  /** Runs the action when the time is up. */
  private final ScheduledExecutorService timer;

  /** What happens when the request runs out of time, such as interrupting its thread. */
  private final Runnable onExpiry;

  /** When the request started, from {@link System#nanoTime()}. */
  private final long startNanos = System.nanoTime();

  /** How long the request may take; zero or less for no limit. */
  private Duration currentLimit = Duration.ZERO;

  /** The pending run of the action, if the request has had a limit. */
  private @Nullable ScheduledFuture<?> pendingExpiry;

  /** Whether the request ran out of time. */
  private boolean expired;

  /** Whether the request ended, after which the action never runs. */
  private boolean closed;

  /**
   * Sets how long the request may take, counted from its start, in place of the limit it had. It
   * changes nothing once the request ran out of time or ended.
   *
   * @param newLimit the limit; zero or less for no limit
   */
  @Synchronized
  public void limitTo(Duration newLimit) {
    if (expired || closed) {
      return;
    }
    cancelPendingExpiry();
    currentLimit = newLimit;
    if (newLimit.isPositive()) {
      var remainingNanos = newLimit.toNanos() - (System.nanoTime() - startNanos);
      pendingExpiry =
          timer.schedule(this::expire, Math.max(remainingNanos, 0), TimeUnit.NANOSECONDS);
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
   * Returns whether the request ran out of time.
   *
   * @return whether the action ran
   */
  @Synchronized
  public boolean hasExpired() {
    return expired;
  }

  /**
   * Ends the request's time limit: the action never runs after this returns. Call it on the
   * request's own thread when the request ends.
   */
  @Synchronized
  public void end() {
    closed = true;
    cancelPendingExpiry();
  }

  /**
   * Runs the action, unless the request ended first or its limit has since grown or gone: a run
   * scheduled for an earlier limit can already have started when {@link #limitTo} cancels it.
   */
  @Synchronized
  private void expire() {
    var timeIsUp =
        currentLimit.isPositive() && System.nanoTime() - startNanos >= currentLimit.toNanos();
    if (!closed && timeIsUp) {
      expired = true;
      onExpiry.run();
    }
  }

  /** Cancels the pending run of the action, if any; cancelling a finished run does nothing. */
  private void cancelPendingExpiry() {
    if (pendingExpiry != null) {
      pendingExpiry.cancel(false);
    }
  }
}
