package com.exepex.stratum.resilience;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.web.RouteValues;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Getter;

/** The limit of each configured route, and the lookup of the route a request's path belongs to. */
@StratumInternal
public class RouteLimits {

  /** The limit of each route, one per configured pattern. */
  @Getter private final List<LimitedRoute> limitedRoutes;

  /** The limits by pattern, the most specific pattern first. */
  private final RouteValues<LimitedRoute> routes;

  /**
   * Makes a limit for each route and parses the patterns.
   *
   * @param incoming the incoming settings, whose routes it uses
   * @throws com.exepex.stratum.core.error.StratumException {@code SX-RESILIENCE-1001} if a route is
   *     not a valid path pattern
   */
  public RouteLimits(ResilienceProperties.IncomingSettings incoming) {
    this.limitedRoutes =
        incoming.routes().entrySet().stream()
            .map(route -> new LimitedRoute(route.getKey(), route.getValue().maxConcurrent()))
            .toList();
    this.routes =
        new RouteValues<>(
            ResilienceConstants.INCOMING_ROUTES,
            limitedRoutes.stream()
                .collect(Collectors.toMap(LimitedRoute::getRoute, Function.identity())),
            ResilienceErrorCode.INVALID_ROUTE_PATTERN);
  }

  /**
   * Returns whether no route is limited, so no request needs a lookup.
   *
   * @return {@code true} with no route set, the default
   */
  public boolean isEmpty() {
    return limitedRoutes.isEmpty();
  }

  /**
   * Returns the limit of the most specific route the path matches.
   *
   * @param pathWithinApplication the request's path, without the application's context path
   * @return the route's limit, or empty when no route matches
   */
  public Optional<LimitedRoute> find(String pathWithinApplication) {
    return routes.find(pathWithinApplication);
  }
}
