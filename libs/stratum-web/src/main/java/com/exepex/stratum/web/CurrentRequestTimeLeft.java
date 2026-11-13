package com.exepex.stratum.web;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.request.RequestTimeLeft;
import java.time.Duration;
import java.util.Optional;

/**
 * The time left of the request whose work runs on the calling thread: the request's own thread, or
 * a thread its deadline was carried to. Outside a request's work there is no limit.
 */
@StratumInternal
public class CurrentRequestTimeLeft implements RequestTimeLeft {

  /** Reads the deadline on the calling thread. */
  private final RequestDeadlineAccessor deadlines = new RequestDeadlineAccessor();

  /**
   * Returns how long the request whose work runs on this thread may still run.
   *
   * @return the time left, zero or negative once the limit has passed; empty outside a request's
   *     work, for a request with no limit and for one that has ended
   */
  @Override
  public Optional<Duration> timeLeft() {
    var deadline = deadlines.getValue();
    return deadline == null ? Optional.empty() : deadline.timeLeft();
  }
}
