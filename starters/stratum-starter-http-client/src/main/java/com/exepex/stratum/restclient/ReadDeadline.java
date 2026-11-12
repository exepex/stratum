package com.exepex.stratum.restclient;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.apache.hc.core5.http.io.HttpClientConnection;
import org.apache.hc.core5.util.Timeout;

/**
 * The deadline of the reads of one answer, its head or its body: each read fails once the call's
 * {@code total-timeout} has passed, and else waits at most the smaller of the client's {@code
 * timeout} and the time left, so that no read waits past the deadline.
 */
@RequiredArgsConstructor
final class ReadDeadline {

  /** The connection the answer comes on. */
  private final HttpClientConnection connection;

  /** The call's deadline, in {@link System#nanoTime()}. */
  private final long deadlineNanos;

  /** The client's {@code timeout}, the longest wait for one read. */
  private final Duration timeout;

  /** The message of the failure once the deadline has passed. */
  private final String tooSlow;

  /** The socket timeout set last, in milliseconds, so that it is set only when it changes. */
  private long socketTimeoutMillis = -1;

  /**
   * Fails once the deadline has passed, else cuts the next read's wait to the time left.
   *
   * @throws SocketTimeoutException once the deadline has passed
   */
  void beforeRead() throws SocketTimeoutException {
    var nanosLeft = deadlineNanos - System.nanoTime();
    if (nanosLeft <= 0) {
      throw new SocketTimeoutException(tooSlow);
    }
    // At least 1ms, as Apache reads 0 as no limit.
    var millis =
        Math.max(1, Math.min(timeout.toMillis(), TimeUnit.NANOSECONDS.toMillis(nanosLeft)));
    if (millis != socketTimeoutMillis) {
      connection.setSocketTimeout(Timeout.ofMilliseconds(millis));
      socketTimeoutMillis = millis;
    }
  }
}
