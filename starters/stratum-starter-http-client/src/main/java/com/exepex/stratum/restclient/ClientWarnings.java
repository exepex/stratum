package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Admits one warning per client per minute, so that a failure repeated on every call, such as a
 * token that cannot be fetched, writes one log line a minute. Its map holds one entry per
 * configured client, so it stays small.
 */
@StratumInternal
@RequiredArgsConstructor
public class ClientWarnings {

  /** How often one client's warning is written at most. */
  private static final Duration INTERVAL = Duration.ofMinutes(1);

  /** Tells the current time; a test sets it. */
  private final Clock clock;

  /** When each client last warned. */
  private final Map<String, Instant> lastWarningAt = new ConcurrentHashMap<>();

  /**
   * Returns whether a warning for the client may be written now: its first, or the first after a
   * minute passed.
   *
   * @param client the client's name
   * @return whether to warn
   */
  public boolean shouldWarn(String client) {
    var now = clock.instant();
    var admitted = new AtomicBoolean();
    lastWarningAt.compute(client, (name, last) -> nextWarningAt(last, now, admitted));
    return admitted.get();
  }

  /**
   * Returns when the client last warned after this check: now if a warning is due, which it marks
   * as admitted, else the time it had.
   */
  private Instant nextWarningAt(@Nullable Instant last, Instant now, AtomicBoolean admitted) {
    var due = last == null || !last.plus(INTERVAL).isAfter(now);
    admitted.set(due);
    return due ? now : last;
  }
}
