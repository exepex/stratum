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
 * pool wait, connect timeout and answer timeout end by the call's {@code total-timeout}, the answer
 * timeout of the call's operation, and the address the attempt was sent to, for the call's log
 * line. Each is a {@link ScopedValue}, bound for one attempt only, so nothing stays behind on the
 * thread.
 */
@StratumInternal
@UtilityClass
public class AttemptTimeLeft {

  /** The deadline of the attempt running on this thread, unbound outside one. */
  private static final ScopedValue<AttemptDeadline> DEADLINE = ScopedValue.newInstance();

  /**
   * The {@code read-timeout} of the operation whose attempt runs on this thread, {@code null} for a
   * call of none; unbound outside an attempt.
   */
  private static final ScopedValue<@Nullable Duration> OPERATION_READ_TIMEOUT =
      ScopedValue.newInstance();

  /**
   * Returns the time left before the deadline of the attempt running on this thread, at least 0.
   *
   * @return the time left, or empty outside a Stratum client's call
   */
  public static Optional<Duration> timeLeft() {
    return DEADLINE.isBound() ? Optional.of(DEADLINE.get().timeLeft()) : Optional.empty();
  }

  /**
   * Returns the {@code read-timeout} of the operation whose attempt runs on this thread.
   *
   * @return the operation's answer timeout, or empty outside a call of an operation, where the
   *     client's applies
   */
  static Optional<Duration> readTimeout() {
    return OPERATION_READ_TIMEOUT.isBound()
        ? Optional.ofNullable(OPERATION_READ_TIMEOUT.get())
        : Optional.empty();
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
   * @param readTimeout the operation's {@code read-timeout}, or {@code null} for the client's
   * @param sentTo where the attempt's address is noted, as a scoped value cannot be bound again
   * @param attempt the attempt
   * @param <T> what the attempt returns
   * @return what the attempt returned
   */
  static <T> T runWithin(
      long deadlineNanos,
      boolean keepsAnswerTimeout,
      @Nullable Duration readTimeout,
      AtomicReference<@Nullable URI> sentTo,
      Supplier<T> attempt) {
    return ScopedValue.where(
            DEADLINE, new AttemptDeadline(deadlineNanos, keepsAnswerTimeout, sentTo))
        .where(OPERATION_READ_TIMEOUT, readTimeout)
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
