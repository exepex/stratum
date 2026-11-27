package com.exepex.stratum.resilience;

import com.exepex.stratum.core.api.StratumInternal;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;

/**
 * Measures each limited route, tagged with its configured pattern: {@code
 * stratum.resilience.requests.refused} counts its refused requests and {@code
 * stratum.resilience.requests.active} shows the requests it runs now. Spring Boot binds it to the
 * service's meter registry; with no route, it adds no meter.
 */
@StratumInternal
@RequiredArgsConstructor
public class RouteMeters implements MeterBinder {

  /** The limited routes. */
  private final RouteLimits routeLimits;

  /**
   * Adds both meters of every limited route.
   *
   * @param registry the service's meter registry
   */
  @Override
  public void bindTo(MeterRegistry registry) {
    routeLimits.getLimitedRoutes().forEach(route -> bindRoute(registry, route));
  }

  /** Adds both meters of one route. */
  private void bindRoute(MeterRegistry registry, LimitedRoute route) {
    FunctionCounter.builder(ResilienceConstants.REFUSED_METER, route, LimitedRoute::refusals)
        .tag(ResilienceConstants.ROUTE_TAG, route.getRoute())
        .description("Requests refused because their route already ran its max-concurrent")
        .register(registry);
    Gauge.builder(ResilienceConstants.ACTIVE_METER, route, LimitedRoute::active)
        .tag(ResilienceConstants.ROUTE_TAG, route.getRoute())
        .description("Requests the route runs now")
        .register(registry);
  }
}
