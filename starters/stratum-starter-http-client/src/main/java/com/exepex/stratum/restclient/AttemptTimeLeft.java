package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * The time left for the attempt of a Stratum client's call that runs on this thread, so that its
 * pool wait, connect timeout and answer timeout end by the call's {@code total-timeout}, and the
 * address the attempt was sent to, for the call's log line. It is a {@link ScopedValue}, bound for
 * one attempt only, so nothing stays behind on the thread.
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
   * Notes the address the attempt running on this thread is sent to; the last one noted is the
   * attempt's. Does nothing outside a Stratum client's call.
   *
   * @param url the address, path and query included
   */
  static void sentTo(URI url) {
    if (DEADLINE.isBound()) {
      DEADLINE.get().sentTo().set(url);
    }
  }

  /**
   * Runs one attempt with its deadline bound.
   *
   * @param deadlineNanos the call's deadline, in {@link System#nanoTime()}
   * @param keepsAnswerTimeout whether the answer timeout stays as set
   * @param sentTo where the attempt's address is noted, as a scoped value cannot be bound again
   * @param attempt the attempt
   * @param <T> what the attempt returns
   * @return what the attempt returned
   */
  static <T> T runWithin(
      long deadlineNanos,
      boolean keepsAnswerTimeout,
      AtomicReference<@Nullable URI> sentTo,
      Supplier<T> attempt) {
    return ScopedValue.where(
            DEADLINE, new AttemptDeadline(deadlineNanos, keepsAnswerTimeout, sentTo))
        .call(attempt::get);
  }

  /**
   * The deadline of one attempt, and where its address is noted.
   *
   * @param deadlineNanos the call's deadline, in {@link System#nanoTime()}
   * @param keepsAnswerTimeout whether the answer timeout stays as set
   * @param sentTo the address the attempt was sent to, empty until it is sent
   */
  private record AttemptDeadline(
      long deadlineNanos, boolean keepsAnswerTimeout, AtomicReference<@Nullable URI> sentTo) {

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
