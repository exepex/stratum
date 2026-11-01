package com.exepex.stratum.web;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Counts each error answer that kept Spring Boot's body in backward-compatible mode because the
 * consumer did not ask for {@code application/problem+json}, tagged with the route, so the team
 * sees which routes still have consumers of the old body. The service can switch the mode off once
 * the counter stays at 0. Nothing is counted when the service has no meter registry.
 */
@RequiredArgsConstructor
public class BootErrorBodyCounter {

  /** The tag that holds the route. */
  private static final String ROUTE = "route";

  /** The service's meter registry, if it has one. */
  private final ObjectProvider<MeterRegistry> meterRegistry;

  /**
   * Counts one error answer with Spring Boot's body.
   *
   * @param route the route the request matched, such as {@code /orders/{orderId}}, or {@code null}
   *     when it matched none of the service's routes
   */
  public void count(@Nullable String route) {
    meterRegistry.ifAvailable(
        registry ->
            Counter.builder(WebConstants.BOOT_ERROR_BODY_COUNTER)
                .description(
                    "Error answers that kept Spring Boot's body because the consumer did not ask"
                        + " for application/problem+json")
                .tag(ROUTE, route == null ? WebConstants.UNKNOWN_ROUTE : route)
                .register(registry)
                .increment());
  }
}
