package com.exepex.stratum.web;

import com.exepex.stratum.core.condition.StratumModuleConstants;
import lombok.experimental.UtilityClass;

/** The property keys, headers and error body members the web module uses (ST-ENG-45). */
@UtilityClass
public class WebConstants {

  /** The module's name, as in {@code stratum.web.enabled}. Both web starters are this module. */
  public static final String MODULE = "web";

  /** The prefix of the module's own settings. */
  public static final String PREFIX = StratumModuleConstants.PREFIX + "." + MODULE;

  /** The switch that turns the module on. */
  public static final String ENABLED = StratumModuleConstants.enabledProperty(MODULE);

  /** The setting that maps a service's error codes to HTTP statuses. */
  public static final String ERROR_STATUSES = PREFIX + ".error.statuses";

  /** The setting that says whether a caller's correlation ID is used. */
  public static final String CORRELATION_ACCEPT_FROM_CALLER =
      PREFIX + ".correlation.accept-from-caller";

  /** The setting that lists the proxies whose {@code X-Forwarded-For} header is trusted. */
  public static final String CLIENT_IP_TRUSTED_PROXIES = PREFIX + ".client-ip.trusted-proxies";

  /** The setting that turns on reading device, OS and browser from the user agent. */
  public static final String USER_AGENT_PARSE = PREFIX + ".user-agent.parse";

  /** The setting that bounds how many parsed user agents are kept. */
  public static final String USER_AGENT_CACHE_SIZE = PREFIX + ".user-agent.cache-size";

  /** The setting that asks browsers for the device model and OS version. */
  public static final String USER_AGENT_REQUEST_CLIENT_HINTS =
      PREFIX + ".user-agent.request-client-hints";

  /** The setting that names the MaxMind database for the client's country and city. */
  public static final String CLIENT_LOCATION_DATABASE = PREFIX + ".client-location.database";

  /** The setting that bounds the length of each request field in the logging context. */
  public static final String REQUEST_FIELDS_MAX_VALUE_LENGTH =
      PREFIX + ".request-fields.max-value-length";

  /** The key under which a request's log fields travel, such as in Reactor's context. */
  public static final String REQUEST_FIELDS_CONTEXT_KEY = "stratum.web.request-fields";

  /** The header that carries the correlation ID, in and out. */
  public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

  /** The error body member that holds the error code, such as {@code SX-WEB-1001}. */
  public static final String CODE = "code";

  /** The error body member that holds the severity: {@code ERROR} or {@code WARNING}. */
  public static final String SEVERITY = "severity";

  /** The error body member that lists the problems with single fields of the request. */
  public static final String DETAILS = "details";
}
