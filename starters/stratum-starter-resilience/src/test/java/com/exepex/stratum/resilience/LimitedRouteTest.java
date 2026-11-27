package com.exepex.stratum.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.time.Duration;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LimitedRoute admits requests up to its limit and refuses the rest at once")
class LimitedRouteTest {

  /** The longest a refusal may take; far shorter than any wait a bulkhead would add. */
  private static final Duration REFUSAL_LIMIT = Duration.ofMillis(500);

  /** A route that runs at most two requests at once. */
  private final LimitedRoute route = new LimitedRoute("/reports/**", 2);

  @Test
  @DisplayName("up to the limit are admitted, the next is refused and counted, none waits")
  void admitsUpToLimit() {
    assertThat(route.tryAdmit()).isTrue();
    assertThat(route.tryAdmit()).isTrue();

    assertThat(route.tryAdmit()).isFalse();
    assertThat(route.active()).isEqualTo(2);
    assertThat(route.refusals()).isEqualTo(1);
  }

  @Test
  @DisplayName("a refusal returns at once: the request never waits for a free permit")
  void refusalNeverWaits() {
    IntStream.range(0, 2).forEach(request -> route.tryAdmit());

    assertThat(assertTimeout(REFUSAL_LIMIT, route::tryAdmit)).isFalse();
  }

  @Test
  @DisplayName("a released permit admits the next request")
  void releaseAdmitsNext() {
    IntStream.range(0, 2).forEach(request -> route.tryAdmit());

    route.release();

    assertThat(route.active()).isEqualTo(1);
    assertThat(route.tryAdmit()).isTrue();
    assertThat(route.refusals()).isZero();
  }
}
