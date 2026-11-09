package com.exepex.stratum.httpclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import java.time.Duration;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/** The names and defaults of the HTTP client module's settings. */
@StratumInternal
@UtilityClass
public class HttpClientConstants {

  /**
   * The module's name, as in {@code stratum.http-client.enabled}. Both HTTP client starters are
   * this module.
   */
  public static final String MODULE = "http-client";

  /** The module's part of its error codes, as in {@code SX-HTTPC-1001}. */
  public static final String ERROR_CODE_MODULE = "HTTPC";

  /** The prefix of the module's own settings. */
  public static final String PREFIX = StratumModuleConstants.PREFIX + "." + MODULE;

  /** The switch that turns the module off. */
  public static final String ENABLED = StratumModuleConstants.enabledProperty(MODULE);

  /** The settings every client shares. */
  public static final String DEFAULTS = PREFIX + ".defaults";

  /** The settings of each client, by its name. */
  public static final String CLIENTS = PREFIX + ".clients";

  /** Spring Boot's settings of each HTTP service group, which do not apply to a Stratum client. */
  public static final String BOOT_SERVICE_CLIENTS = "spring.http.serviceclient";

  /** Spring Boot's choice of engine for the {@code RestClient} it builds. */
  public static final String BOOT_IMPERATIVE_FACTORY = "spring.http.clients.imperative.factory";

  /** Stratum's default for {@link #BOOT_IMPERATIVE_FACTORY}: Java's own HTTP client. */
  public static final String JDK_FACTORY = "jdk";

  /** What a client's name looks like: lowercase letters, digits and hyphens, from a letter. */
  public static final Pattern CLIENT_NAME = Pattern.compile("[a-z][a-z0-9-]*");

  /** Stratum's default time allowed to open a connection. */
  public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /** Stratum's default time allowed for the answer to one call. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

  /** Stratum's default size of each client's connection pool. */
  public static final int DEFAULT_MAX_CONNECTIONS = 50;

  /** The largest connection pool a client may have. */
  public static final int MAX_MAX_CONNECTIONS = 10_000;

  /** Stratum's default time a call waits for a free connection. */
  public static final Duration DEFAULT_MAX_WAIT = Duration.ofSeconds(2);

  /** Stratum's default time after which an unused connection is closed. */
  public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(30);

  /** Stratum's default age after which a connection is not used again. */
  public static final Duration DEFAULT_CONNECTION_LIFETIME = Duration.ofMinutes(5);

  /** Stratum's default number of times a failed call is sent again. */
  public static final int DEFAULT_RETRIES = 3;

  /** The most times a failed call may be sent again. */
  public static final int MAX_RETRIES = 9;

  /** Stratum's default wait before the first retry. */
  public static final Duration DEFAULT_RETRY_DELAY = Duration.ofMillis(200);

  /** The longest wait between two attempts, after doubling and random spread (ADR 0011, 2.4). */
  public static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(2);

  /** Stratum's default time allowed for a whole call, below the web starter's 10s answer limit. */
  public static final Duration DEFAULT_TOTAL_TIMEOUT = Duration.ofSeconds(8);

  /** The least time left before a call's deadline for another attempt to start (ADR 0011, 4.4). */
  public static final Duration MIN_ATTEMPT_TIME = Duration.ofMillis(100);

  /**
   * The order of the retries and circuit breaker around each call of a client, the outermost of
   * Stratum's decorators; a later one, such as the resend after a 401, runs inside them.
   */
  public static final int RESILIENCE_ORDER = 0;

  /**
   * How long a connection may sit unused before it is checked before its next use, so that one the
   * server closed meanwhile is not used. Fixed, with no key (ADR 0011, decision 5.2).
   */
  public static final Duration STALE_CHECK_AFTER = Duration.ofSeconds(2);

  /** The header that carries a client's API key when it names none. */
  public static final String DEFAULT_API_KEY_HEADER = "X-Api-Key";
}
