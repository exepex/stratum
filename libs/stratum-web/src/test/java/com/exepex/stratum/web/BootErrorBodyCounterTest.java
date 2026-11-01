package com.exepex.stratum.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

@DisplayName("BootErrorBodyCounter counts the error answers with Spring Boot's body by route")
class BootErrorBodyCounterTest {

  /** A route of the service. */
  private static final String ROUTE = "/orders/{orderId}";

  /** The registry the counter writes to. */
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @Test
  @DisplayName("counts each answer under the route it matched")
  void countsByRoute() {
    var counter = counterWith(Map.of("meterRegistry", registry));

    counter.count(ROUTE);
    counter.count(ROUTE);

    assertThat(countOf(ROUTE)).isEqualTo(2);
  }

  @Test
  @DisplayName("counts a request that matched no route under UNKNOWN")
  void countsUnknownRoute() {
    counterWith(Map.of("meterRegistry", registry)).count(null);

    assertThat(countOf(WebConstants.UNKNOWN_ROUTE)).isEqualTo(1);
  }

  @Test
  @DisplayName("does nothing when the service has no meter registry")
  void noRegistryCountsNothing() {
    var counter = counterWith(Map.of());

    assertThatNoException().isThrownBy(() -> counter.count(ROUTE));
  }

  /** Returns a counter whose registry, if any, is among the given beans. */
  private BootErrorBodyCounter counterWith(Map<String, Object> beans) {
    return new BootErrorBodyCounter(
        new StaticListableBeanFactory(beans).getBeanProvider(MeterRegistry.class));
  }

  /** Returns how many answers the counter holds for a route. */
  private double countOf(String route) {
    return registry.get(WebConstants.BOOT_ERROR_BODY_COUNTER).tag("route", route).counter().count();
  }
}
