package com.exepex.stratum.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.resilience.testsupport.ResilienceTestData;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RouteMeters measures each limited route, tagged with its pattern")
class RouteMetersTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @Test
  @DisplayName("the refused counter and the active gauge follow the route")
  void measuresRoute() {
    var limits = new RouteLimits(ResilienceTestData.incoming(Map.of("/reports/**", 1)).build());
    new RouteMeters(limits).bindTo(registry);
    var route = limits.getLimitedRoutes().getFirst();

    route.tryAdmit();
    route.tryAdmit();

    assertThat(
            registry
                .get(ResilienceConstants.REFUSED_METER)
                .tag(ResilienceConstants.ROUTE_TAG, "/reports/**")
                .functionCounter()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get(ResilienceConstants.ACTIVE_METER)
                .tag(ResilienceConstants.ROUTE_TAG, "/reports/**")
                .gauge()
                .value())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("with no route, no meter is added")
  void noRouteNoMeter() {
    new RouteMeters(new RouteLimits(ResilienceTestData.incoming(Map.of()).build()))
        .bindTo(registry);

    assertThat(registry.getMeters()).isEmpty();
  }
}
