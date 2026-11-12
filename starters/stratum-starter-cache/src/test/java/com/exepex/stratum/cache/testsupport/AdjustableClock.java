package com.exepex.stratum.cache.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import lombok.AllArgsConstructor;

/** A clock a test sets and advances, for the time-based warning throttle. */
@AllArgsConstructor
public final class AdjustableClock extends Clock {

  /** The current time. */
  private Instant now;

  /**
   * Moves the clock forward.
   *
   * @param duration how far
   */
  public void advance(Duration duration) {
    now = now.plus(duration);
  }

  @Override
  public Instant instant() {
    return now;
  }

  @Override
  public ZoneId getZone() {
    return ZoneId.systemDefault();
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }
}
