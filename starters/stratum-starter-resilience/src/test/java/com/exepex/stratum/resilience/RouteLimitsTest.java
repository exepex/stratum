package com.exepex.stratum.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.resilience.testsupport.ResilienceTestData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

@DisplayName("RouteLimits finds the limit of the most specific route a path matches")
class RouteLimitsTest {

  /** Returns the limits of the routes. */
  private RouteLimits limits(Map<String, Integer> routes) {
    return new RouteLimits(ResilienceTestData.incoming(routes).build());
  }

  @Nested
  @DisplayName("finding a route")
  class Finding {

    /** A broad route and a more specific one inside it. */
    private final RouteLimits limits =
        limits(Map.of(ResilienceTestData.REPORTS_ROUTE, 10, ResilienceTestData.EXPORT_ROUTE, 2));

    @Test
    @DisplayName("the most specific matching route wins")
    void mostSpecificWins() {
      assertThat(limits.find("/reports/7/export"))
          .hasValueSatisfying(
              route -> assertThat(route.getRoute()).isEqualTo(ResilienceTestData.EXPORT_ROUTE));
      assertThat(limits.find("/reports/7"))
          .hasValueSatisfying(
              route -> assertThat(route.getRoute()).isEqualTo(ResilienceTestData.REPORTS_ROUTE));
    }

    @Test
    @DisplayName("a path no route matches has no limit")
    void unmatchedPath() {
      assertThat(limits.find("/orders/7")).isEmpty();
      assertThat(limits.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("each route has one limit, the same on every lookup")
    void oneLimitPerRoute() {
      assertThat(limits.getLimitedRoutes()).hasSize(2);
      assertThat(limits.find("/reports/1")).containsSame(limits.find("/reports/2").orElseThrow());
    }
  }

  @Nested
  @DisplayName("with no route")
  class NoRoute {

    @Test
    @DisplayName("it is empty and finds nothing")
    void isEmpty() {
      var limits = limits(Map.of());

      assertThat(limits.isEmpty()).isTrue();
      assertThat(limits.find("/reports/1")).isEmpty();
    }
  }

  @Nested
  @DisplayName("with a pattern that is not valid")
  class InvalidPattern {

    /** Patterns Spring refuses: a variable not closed, and {@code **} inside the pattern. */
    private static final List<String> INVALID_PATTERNS = List.of("/api/{id", "/api/**/orders");

    @ParameterizedTest
    @FieldSource("INVALID_PATTERNS")
    @DisplayName("startup stops with SX-RESILIENCE-1001, naming the key and the pattern")
    void stopsStartup(String pattern) {
      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(() -> limits(Map.of(pattern, 1)))
          .withMessageStartingWith(
              "SX-RESILIENCE-1001: A route of stratum.resilience.incoming.routes is not a valid"
                  + " path pattern: ")
          .withMessageContaining(ResilienceConstants.INCOMING_ROUTES + " has '" + pattern + "' (");
    }
  }
}
