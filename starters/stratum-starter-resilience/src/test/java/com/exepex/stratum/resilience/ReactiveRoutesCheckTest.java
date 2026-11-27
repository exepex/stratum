package com.exepex.stratum.resilience;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.resilience.testsupport.ResilienceTestData;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ReactiveRoutesCheck stops a reactive application only when routes are set")
class ReactiveRoutesCheckTest {

  /** Returns the check of settings with the routes. */
  private ReactiveRoutesCheck check(Map<String, Integer> routes) {
    var settings =
        ResilienceTestData.defaults().toBuilder()
            .incoming(ResilienceTestData.incoming(routes).build())
            .build();
    return new ReactiveRoutesCheck(settings);
  }

  @Test
  @DisplayName("routes stop startup with SX-RESILIENCE-1002, naming the key and issue #420")
  void routesStopStartup() {
    var check = check(Map.of(ResilienceTestData.REPORTS_ROUTE, 1));

    assertThatExceptionOfType(StratumException.class)
        .isThrownBy(check::afterPropertiesSet)
        .withMessageStartingWith("SX-RESILIENCE-1002: ")
        .withMessageContaining(ResilienceConstants.INCOMING_ROUTES)
        .withMessageContaining("issues/420");
  }

  @Test
  @DisplayName("no route lets the application start")
  void noRouteStarts() {
    assertThatNoException().isThrownBy(check(Map.of())::afterPropertiesSet);
  }
}
