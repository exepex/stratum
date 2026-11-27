package com.exepex.stratum.resilience.testsupport;

import com.exepex.stratum.resilience.ResilienceConstants;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;

/** Reads a route's meters from a test service's registry. */
@RequiredArgsConstructor
public class RouteMeterReader {

  /** The service's meter registry. */
  private final MeterRegistry meterRegistry;

  /** The route's configured pattern. */
  private final String route;

  /**
   * Returns the requests the route refused since startup.
   *
   * @return the count
   */
  public double refused() {
    return meterRegistry
        .get(ResilienceConstants.REFUSED_METER)
        .tag(ResilienceConstants.ROUTE_TAG, route)
        .functionCounter()
        .count();
  }

  /**
   * Returns the requests the route runs now.
   *
   * @return the count
   */
  public double active() {
    return meterRegistry
        .get(ResilienceConstants.ACTIVE_METER)
        .tag(ResilienceConstants.ROUTE_TAG, route)
        .gauge()
        .value();
  }
}
