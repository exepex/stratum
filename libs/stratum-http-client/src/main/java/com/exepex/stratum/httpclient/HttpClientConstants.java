package com.exepex.stratum.httpclient;

import com.exepex.stratum.core.condition.StratumModuleConstants;
import java.time.Duration;
import lombok.experimental.UtilityClass;

/** The property keys and defaults the HTTP client module uses (ST-ENG-45). */
@UtilityClass
public class HttpClientConstants {

  /**
   * The module's name, as in {@code stratum.http-client.enabled}. Both HTTP client starters are
   * this module.
   */
  public static final String MODULE = "http-client";

  /** The prefix of the module's own settings. */
  public static final String PREFIX = StratumModuleConstants.PREFIX + "." + MODULE;

  /** The switch that turns the module on. */
  public static final String ENABLED = StratumModuleConstants.enabledProperty(MODULE);

  /** The setting that bounds the connections to each downstream service. */
  public static final String POOL_MAX_CONNECTIONS = PREFIX + ".pool.max-connections";

  /** The setting that bounds how long a call waits for a free connection. */
  public static final String POOL_MAX_WAIT = PREFIX + ".pool.max-wait";

  /** Spring Boot's time allowed to open a connection, for every client. */
  public static final String CONNECT_TIMEOUT = "spring.http.clients.connect-timeout";

  /** Spring Boot's time allowed to wait for data of the response, for every client. */
  public static final String READ_TIMEOUT = "spring.http.clients.read-timeout";

  /** Stratum's default for {@link #CONNECT_TIMEOUT}. */
  public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /** Stratum's default for {@link #READ_TIMEOUT}. */
  public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(5);

  /**
   * The order of Stratum's customizers of Spring Boot's client builders: after Spring Boot's own,
   * which have order 0, and before a service's customizers that declare no order, so the service's
   * win.
   */
  public static final int CUSTOMIZER_ORDER = 1;

  /** The name of the reactive connection pool, as its metrics show it in their name tag. */
  public static final String POOL_NAME = "stratum-http-client";

  /**
   * Micrometer's registry, named as text so that a service without Micrometer can still load the
   * condition that looks for it.
   */
  public static final String METER_REGISTRY = "io.micrometer.core.instrument.MeterRegistry";
}
