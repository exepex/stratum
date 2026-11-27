package com.exepex.stratum.resilience;

import com.exepex.stratum.core.api.StratumInternal;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import java.time.Duration;
import java.util.concurrent.atomic.LongAdder;
import lombok.Getter;

/**
 * One route's limit: a Resilience4j semaphore bulkhead that admits at most {@code max-concurrent}
 * requests at once and never makes one wait, and the count of the requests it refused.
 */
@StratumInternal
public class LimitedRoute {

  /** The route's configured path pattern, which the meters carry as their tag. */
  @Getter private final String route;

  /** The most requests the route runs at once. */
  private final int maxConcurrent;

  /** Holds a permit for each request the route runs. */
  private final Bulkhead bulkhead;

  /** The requests refused since startup. */
  private final LongAdder refusedCount = new LongAdder();

  /**
   * Makes the route's limit.
   *
   * @param route the route's path pattern, such as {@code /reports/**}
   * @param maxConcurrent the most requests it runs at once
   */
  public LimitedRoute(String route, int maxConcurrent) {
    this.route = route;
    this.maxConcurrent = maxConcurrent;
    this.bulkhead =
        Bulkhead.of(
            route,
            BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrent)
                .maxWaitDuration(Duration.ZERO)
                .build());
  }

  /**
   * Takes a permit for a request when the route has one free, or counts the request as refused.
   *
   * @return whether the request may run; then {@link #release()} must follow once, when it ends
   */
  public boolean tryAdmit() {
    var admitted = bulkhead.tryAcquirePermission();
    if (!admitted) {
      refusedCount.increment();
    }
    return admitted;
  }

  /** Gives back the permit of a request that has ended. */
  public void release() {
    bulkhead.onComplete();
  }

  /**
   * Returns how many requests the route runs now.
   *
   * @return the permits in use
   */
  public int active() {
    return maxConcurrent - bulkhead.getMetrics().getAvailableConcurrentCalls();
  }

  /**
   * Returns how many requests the route refused since startup.
   *
   * @return the count
   */
  public long refusals() {
    return refusedCount.sum();
  }
}
