package com.exepex.stratum.resilience.testsupport;

import com.exepex.stratum.resilience.ResilienceProperties;
import com.exepex.stratum.resilience.ResilienceProperties.RouteLimit;
import com.exepex.stratum.resilience.ResiliencePropertiesCustomizer;
import java.time.Duration;
import java.util.Map;

/** A service's Java configuration of the resilience module, as the module page shows it. */
public class OrdersResilienceSettings implements ResiliencePropertiesCustomizer {

  /** The limit this configuration gives the reports route, which wins over the files' 10. */
  public static final int REPORTS_LIMIT = 3;

  /** The wait this configuration gives refused callers, which wins over the files' 2s. */
  public static final Duration RETRY_AFTER = Duration.ofSeconds(5);

  /**
   * Sets two routes and a wait of 5s.
   *
   * @param settings the settings from the files
   * @return the changed settings
   */
  @Override
  public ResilienceProperties customize(ResilienceProperties settings) {
    var incoming =
        settings.incoming().toBuilder()
            .routes(
                Map.of(
                    ResilienceTestData.REPORTS_ROUTE,
                    RouteLimit.builder().maxConcurrent(REPORTS_LIMIT).build(),
                    "/exports/{id}",
                    RouteLimit.builder().maxConcurrent(2).build()))
            .retryAfter(RETRY_AFTER)
            .build();
    return settings.toBuilder().incoming(incoming).build();
  }
}
