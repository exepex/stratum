package com.exepex.stratum.core.executor;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A scheduled pool that gives each one-shot task the logging and tracing context of the code that
 * submitted it, as Micrometer's {@code ContextScheduledExecutorService} does, but runs a periodic
 * task without it. A periodic task keeps running long after the code that scheduled it, such as a
 * request, has ended; replaying that request's trace and log fields on every run would tie
 * unrelated work to it.
 */
public final class OneShotContextScheduledExecutorService
    extends ContextExecutorService<ScheduledExecutorService> implements ScheduledExecutorService {

  /** Captures the context from every registered source, as Micrometer's own wrappers do. */
  private static final ContextSnapshotFactory SNAPSHOTS = ContextSnapshotFactory.builder().build();

  /**
   * Wraps the pool.
   *
   * @param pool the service's scheduled pool, which runs every task
   */
  public OneShotContextScheduledExecutorService(ScheduledExecutorService pool) {
    super(pool, () -> SNAPSHOTS.captureAll());
  }

  /**
   * Runs the task once, after the delay, with the caller's context.
   *
   * @param command the task
   * @param delay how long to wait
   * @param unit the unit of the delay
   * @return the task's future
   */
  @Override
  public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
    return getExecutorService().schedule(capture().wrap(command), delay, unit);
  }

  /**
   * Runs the task once, after the delay, with the caller's context.
   *
   * @param callable the task
   * @param delay how long to wait
   * @param unit the unit of the delay
   * @param <V> the task's result type
   * @return the task's future
   */
  @Override
  public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
    return getExecutorService().schedule(capture().wrap(callable), delay, unit);
  }

  /**
   * Runs the task periodically, without the caller's context.
   *
   * @param command the task
   * @param initialDelay how long to wait before the first run
   * @param period the time between the starts of two runs
   * @param unit the unit of the delay and the period
   * @return the task's future
   */
  @Override
  public ScheduledFuture<?> scheduleAtFixedRate(
      Runnable command, long initialDelay, long period, TimeUnit unit) {
    return getExecutorService().scheduleAtFixedRate(command, initialDelay, period, unit);
  }

  /**
   * Runs the task periodically, without the caller's context.
   *
   * @param command the task
   * @param initialDelay how long to wait before the first run
   * @param delay the time between the end of one run and the start of the next
   * @param unit the unit of the delays
   * @return the task's future
   */
  @Override
  public ScheduledFuture<?> scheduleWithFixedDelay(
      Runnable command, long initialDelay, long delay, TimeUnit unit) {
    return getExecutorService().scheduleWithFixedDelay(command, initialDelay, delay, unit);
  }
}
