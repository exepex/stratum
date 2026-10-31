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

  /** The switch for the one log line each request writes. */
  public static final String REQUEST_LOG_ENABLED = PREFIX + ".request-log.enabled";

  /** The setting that lists the paths whose requests write no log line. */
  public static final String REQUEST_LOG_SKIP_PATHS = PREFIX + ".request-log.skip-paths";

  /** The setting that names the request headers the request log line carries. */
  public static final String REQUEST_LOG_REQUEST_HEADERS = PREFIX + ".request-log.request-headers";

  /** The setting that names the response headers the request log line carries. */
  public static final String REQUEST_LOG_RESPONSE_HEADERS =
      PREFIX + ".request-log.response-headers";

  /** The setting that lists more headers that are never logged. */
  public static final String REQUEST_LOG_DENIED_HEADERS = PREFIX + ".request-log.denied-headers";

  /** The setting that turns on logging the caller's token payload. */
  public static final String REQUEST_LOG_TOKEN_PAYLOAD = PREFIX + ".request-log.token-payload";

  /** The setting that lists the claims of the caller's token payload that are logged. */
  public static final String REQUEST_LOG_TOKEN_CLAIMS = PREFIX + ".request-log.token-claims";

  /** The setting that limits how long any request may take until its response starts. */
  public static final String TIMEOUT_DEFAULT = PREFIX + ".timeout.default";

  /** The setting that limits how long the requests on some paths may take. */
  public static final String TIMEOUT_ROUTES = PREFIX + ".timeout.routes";

  /** The setting that names the status of a request that ran out of time. */
  public static final String TIMEOUT_STATUS = PREFIX + ".timeout.status";

  /** The logger of the request log lines, whose level a service can change. */
  public static final String REQUEST_LOG_LOGGER = "com.exepex.stratum.web.request-log";

  /** The request log field that holds the response's HTTP status. */
  public static final String HTTP_STATUS = "httpStatus";

  /** The request log field that holds how long the request took, in milliseconds. */
  public static final String DURATION_MS = "durationMs";

  /**
   * The request log field that holds the listed claims of the caller's token payload. Its name has
   * no "token" in it, because the logging starter masks every field so named in full.
   */
  public static final String JWT_CLAIMS = "jwtClaims";

  /** The key under which a request's log fields travel, such as in Reactor's context. */
  public static final String REQUEST_FIELDS_CONTEXT_KEY = "stratum.web.request-fields";

  /** The W3C header that carries the tracing baggage, and the correlation ID in it. */
  public static final String BAGGAGE_HEADER = "baggage";

  /** The header that carries the correlation ID, in and out. */
  public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

  /** The response header that asks browsers for client hints, such as the device model. */
  public static final String ACCEPT_CLIENT_HINTS_HEADER = "Accept-CH";

  /** The error body member that holds the error code, such as {@code SX-WEB-1001}. */
  public static final String CODE = "code";

  /** The error body member that holds the severity: {@code ERROR} or {@code WARNING}. */
  public static final String SEVERITY = "severity";

  /** The error body member that lists the problems with single fields of the request. */
  public static final String DETAILS = "details";
}
