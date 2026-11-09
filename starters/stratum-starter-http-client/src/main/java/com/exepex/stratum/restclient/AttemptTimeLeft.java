package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.experimental.UtilityClass;

/**
 * The time left for the attempt of a Stratum client's call that runs on this thread, so that its
 * pool wait, connect timeout and answer timeout end by the call's {@code total-timeout}. It is a
 * {@link ScopedValue}, bound for one attempt only, so nothing stays behind on the thread.
 */
@StratumInternal
@UtilityClass
public class AttemptTimeLeft {

  /** The deadline of the attempt running on this thread, unbound outside one. */
  private static final ScopedValue<AttemptDeadline> DEADLINE = ScopedValue.newInstance();

  /**
   * Returns the time left before the deadline of the attempt running on this thread, at least 0.
   *
   * @return the time left, or empty outside a Stratum client's call
   */
  public static Optional<Duration> timeLeft() {
    return DEADLINE.isBound() ? Optional.of(DEADLINE.get().timeLeft()) : Optional.empty();
  }

  /**
   * Returns whether the attempt running on this thread keeps its full answer timeout, as a streamed
   * download does, whose answer timeout is the longest gap between bytes.
   *
   * @return whether the answer timeout stays as set
   */
  static boolean keepsAnswerTimeout() {
    return DEADLINE.isBound() && DEADLINE.get().keepsAnswerTimeout();
  }

  /**
   * Runs one attempt with its deadline bound.
   *
   * @param deadlineNanos the call's deadline, in {@link System#nanoTime()}
   * @param keepsAnswerTimeout whether the answer timeout stays as set
   * @param attempt the attempt
   * @param <T> what the attempt returns
   * @return what the attempt returned
   */
  static <T> T runWithin(long deadlineNanos, boolean keepsAnswerTimeout, Supplier<T> attempt) {
    return ScopedValue.where(DEADLINE, new AttemptDeadline(deadlineNanos, keepsAnswerTimeout))
        .call(attempt::get);
  }

  /**
   * The deadline of one attempt.
   *
   * @param deadlineNanos the call's deadline, in {@link System#nanoTime()}
   * @param keepsAnswerTimeout whether the answer timeout stays as set
   */
  private record AttemptDeadline(long deadlineNanos, boolean keepsAnswerTimeout) {

    /**
     * Returns the time left before the deadline, at least 0.
     *
     * @return the time left
     */
    Duration timeLeft() {
      return Duration.ofNanos(Math.max(0, deadlineNanos - System.nanoTime()));
    }
  }
}
