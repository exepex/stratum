package com.exepex.stratum.core.request;

import java.time.Duration;
import java.util.Optional;

/**
 * How long a request has left before its time limit, read live: a limit that changes while the
 * request runs is seen at once. The web modules provide it as a bean, which reads the request whose
 * work runs on the calling thread; a service injects it to fit its own remote calls within the
 * request's limit.
 */
@FunctionalInterface
public interface RequestTimeLeft {

  /**
   * Returns how long the request may still run.
   *
   * @return the time left, zero or negative once the limit has passed; empty when there is no
   *     limit: no request, a limit of 0, or a request that has ended
   */
  Optional<Duration> timeLeft();
}
