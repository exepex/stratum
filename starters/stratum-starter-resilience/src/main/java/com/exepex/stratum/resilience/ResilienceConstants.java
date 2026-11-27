package com.exepex.stratum.resilience;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import lombok.experimental.UtilityClass;

/** The property keys, meter names and other names the resilience module uses (ST-ENG-45). */
@StratumInternal
@UtilityClass
public class ResilienceConstants {

  /** The module's name, as its switch {@code stratum.resilience.enabled} spells it. */
  public static final String MODULE = "resilience";

  /** The module's on/off property. */
  public static final String ENABLED = StratumModuleConstants.enabledProperty(MODULE);

  /** The part of the error codes that names this module, as in {@code SX-RESILIENCE-1001}. */
  public static final String ERROR_CODE_MODULE = "RESILIENCE";

  /** The first part of the module's own settings. */
  public static final String PREFIX = StratumModuleConstants.PREFIX + "." + MODULE;

  /** The settings of the work that comes into the service, such as its requests. */
  public static final String INCOMING = PREFIX + ".incoming";

  /** The limit of each route, as in {@code stratum.resilience.incoming.routes[/reports/**]}. */
  public static final String INCOMING_ROUTES = INCOMING + ".routes";

  /** How long a refused caller is told to wait before it sends the request again. */
  public static final String RETRY_AFTER = INCOMING + ".retry-after";

  /** The most requests a route's {@code max-concurrent} may admit at once. */
  public static final int MAX_CONCURRENT_LIMIT = 100_000;

  /** The counter of the requests refused because their route was full. */
  public static final String REFUSED_METER = PREFIX + ".requests.refused";

  /** The gauge of the requests each route runs now. */
  public static final String ACTIVE_METER = PREFIX + ".requests.active";

  /** The tag of both meters that names the route, as its configured pattern. */
  public static final String ROUTE_TAG = "route";

  /** The name of Spring MVC's bean that runs its exception resolvers in their order. */
  public static final String HANDLER_EXCEPTION_RESOLVER = "handlerExceptionResolver";

  /** The name of the bean that holds the actuator paths a route never counts. */
  public static final String UNCOUNTED_PATHS = "stratumUncountedPaths";

  /** The issue that plans the limits for WebFlux. */
  public static final String WEBFLUX_ISSUE = "https://github.com/exepex/stratum/issues/420";
}
