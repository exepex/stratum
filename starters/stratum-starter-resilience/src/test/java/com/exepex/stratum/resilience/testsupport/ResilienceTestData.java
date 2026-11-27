package com.exepex.stratum.resilience.testsupport;

import com.exepex.stratum.resilience.ResilienceConstants;
import com.exepex.stratum.resilience.ResilienceProperties;
import com.exepex.stratum.resilience.ResilienceProperties.IncomingSettings;
import com.exepex.stratum.resilience.ResilienceProperties.RouteLimit;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;
import org.springframework.mock.env.MockEnvironment;

/** Settings and names shared by the resilience module's tests. */
@UtilityClass
public class ResilienceTestData {

  /** A route pattern a service limits, as its reports endpoints. */
  public static final String REPORTS_ROUTE = "/reports/**";

  /** A more specific route pattern inside {@link #REPORTS_ROUTE}. */
  public static final String EXPORT_ROUTE = "/reports/{id}/export";

  /** The key of the limit of {@link #REPORTS_ROUTE}, as a .properties file writes it. */
  public static final String REPORTS_MAX_CONCURRENT =
      ResilienceConstants.INCOMING_ROUTES + "[" + REPORTS_ROUTE + "].max-concurrent";

  /** The key of the status of a refused request, as a .properties file writes it. */
  public static final String REFUSED_STATUS = "stratum.web.error.statuses.SX-RESILIENCE-0001";

  /** The code of a refused request. */
  public static final String REFUSED_CODE = "SX-RESILIENCE-0001";

  /** The name of the tests that show a service's Java configuration wins. */
  public static final String JAVA_CONFIGURATION_WINS =
      "a customizer listed in spring.factories wins over the files";

  /**
   * Returns a builder of the incoming settings with a limit of each route.
   *
   * @param limits the most requests each route pattern runs at once
   * @return the builder, with the default retry-after
   */
  public static IncomingSettings.IncomingSettingsBuilder incoming(Map<String, Integer> limits) {
    return defaults().incoming().toBuilder()
        .routes(
            limits.entrySet().stream()
                .collect(
                    Collectors.toMap(
                        Map.Entry::getKey,
                        limit -> RouteLimit.builder().maxConcurrent(limit.getValue()).build())));
  }

  /**
   * Returns the resilience settings a service gets when it sets nothing.
   *
   * @return the settings
   */
  public static ResilienceProperties defaults() {
    return ResilienceProperties.resolve(new MockEnvironment());
  }
}
