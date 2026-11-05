package com.exepex.stratum.observability.testsupport;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/** Keeps the logging context that each received message was handled in, in arrival order. */
public class ReceivedLoggingContexts {

  /** How long a test waits for a message to arrive. */
  private static final Duration RECEIVE_TIMEOUT = Duration.ofSeconds(30);

  /** The logging context of each received message, in the order they arrived. */
  private final BlockingQueue<Map<String, String>> loggingContexts = new LinkedBlockingQueue<>();

  /** Keeps a copy of the logging context the current message is handled in. */
  public void keepCurrent() {
    loggingContexts.add(
        Map.copyOf(Objects.requireNonNullElse(MDC.getCopyOfContextMap(), Map.of())));
  }

  /**
   * Returns the logging context of the next received message, or {@code null} if none arrives in
   * time.
   *
   * @return the logging context's keys and values
   * @throws InterruptedException if the test is stopped while waiting
   */
  public @Nullable Map<String, String> next() throws InterruptedException {
    return loggingContexts.poll(RECEIVE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
  }
}
