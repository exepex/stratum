package com.exepex.stratum.httpclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import org.springframework.util.unit.DataSize;

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

  /**
   * Stratum's default longest wait for the first byte of an answer and between two of its bytes.
   */
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

  /**
   * Stratum's default longest wait between two attempts, after growth and random spread, used when
   * {@code max-delay} is unset and {@code delay} is shorter (ADR 0011, 2.4).
   */
  public static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(2);

  /** Stratum's default growth of each wait before a retry: it doubles. */
  public static final double DEFAULT_MULTIPLIER = 2.0;

  /** The least growth of each wait before a retry: it stays the same. */
  public static final double MIN_MULTIPLIER = 1.0;

  /** The most growth of each wait before a retry. */
  public static final double MAX_MULTIPLIER = 10.0;

  /** Stratum's default share of failed attempts, in percent, that opens a circuit breaker. */
  public static final float DEFAULT_FAILURE_RATE_THRESHOLD = 50;

  /** The lowest share, in percent, a breaker's failure or slow-call rate may be set to. */
  public static final float MIN_RATE_THRESHOLD = 1;

  /** The highest share, in percent, a breaker's failure or slow-call rate may be set to. */
  public static final float MAX_RATE_THRESHOLD = 100;

  /** Stratum's default number of latest attempts a breaker's failure rate is taken over. */
  public static final int DEFAULT_SLIDING_WINDOW_SIZE = 100;

  /** Stratum's default number of attempts a breaker must see before it may open. */
  public static final int DEFAULT_MINIMUM_NUMBER_OF_CALLS = 100;

  /** The most attempts a breaker's window or minimum may hold, which bounds its memory. */
  public static final int MAX_BREAKER_CALLS = 10_000;

  /** Stratum's default time an open breaker fails every call before it lets trial calls through. */
  public static final Duration DEFAULT_WAIT_IN_OPEN_STATE = Duration.ofSeconds(60);

  /** Stratum's default number of trial calls a breaker lets through after its open state. */
  public static final int DEFAULT_HALF_OPEN_CALLS = 10;

  /** The most trial calls a breaker may let through after its open state. */
  public static final int MAX_HALF_OPEN_CALLS = 1000;

  /** Stratum's default time allowed for a whole call, below the web starter's 10s answer limit. */
  public static final Duration DEFAULT_TOTAL_TIMEOUT = Duration.ofSeconds(8);

  /** The least time left before a call's deadline for another attempt to start (ADR 0011, 4.4). */
  public static final Duration MIN_ATTEMPT_TIME = Duration.ofMillis(100);

  /**
   * The order of the retries and circuit breaker around each call of a client; a later one, such as
   * the resend after a 401, runs inside them.
   */
  public static final int RESILIENCE_ORDER = 0;

  /**
   * The order of the reading of a 404 as an empty {@code Optional}, outside the retries and breaker
   * and inside the error translation, so that no customizer's {@code mapError} sees that 404.
   */
  public static final int OPTIONAL_NOT_FOUND_ORDER = RESILIENCE_ORDER - 1;

  /**
   * The order of the error translation around each call of a client, the outermost of Stratum's
   * decorators, so that the retries and breaker see Spring's own exceptions.
   */
  public static final int ERROR_TRANSLATION_ORDER = OPTIONAL_NOT_FOUND_ORDER - 1;

  /**
   * The most of an error answer's body that is read, 64 KB; the rest is never held. Fixed, with no
   * key (ADR 0011, decision 6.2).
   */
  public static final int ERROR_BODY_LIMIT = 64 * 1024;

  /**
   * The attribute that marks a call of a client's interface whose method returns {@code Optional},
   * so that a 404 answers it with an empty {@code Optional}.
   */
  public static final String OPTIONAL_ANSWER = PREFIX + ".optional-answer";

  /** The web starter's key that maps an error code to the HTTP status its caller gets. */
  public static final String WEB_ERROR_STATUSES = "stratum.web.error.statuses";

  /**
   * How long a connection may sit unused before it is checked before its next use, so that one the
   * server closed meanwhile is not used. Fixed, with no key (ADR 0011, decision 5.2).
   */
  public static final Duration STALE_CHECK_AFTER = Duration.ofSeconds(2);

  /**
   * The most characters of a URI template the call log writes when no redaction runs over the line,
   * cut at its first {@code ?} too. Fixed, with no key, as ADR 0011 decision 2.4 fixes the keys.
   */
  public static final int MAX_LOGGED_URI_LENGTH = 256;

  /** The prefix of the names of the meters of Stratum's clients. */
  public static final String METER_PREFIX = "stratum.http.client";

  /** The timer of each whole call, retries and waits included, tagged by client and outcome. */
  public static final String CALLS_METER = METER_PREFIX + ".calls";

  /** The counter of the attempts after the first, tagged by client. */
  public static final String RETRIES_METER = METER_PREFIX + ".retries";

  /** The gauge of each state of a client's circuit breaker, 1 for the current one. */
  public static final String BREAKER_STATE_METER = METER_PREFIX + ".circuitbreaker.state";

  /** The counter of the attempts a full pool refused within {@code max-wait}. */
  public static final String POOL_REFUSED_METER = METER_PREFIX + ".pool.refused";

  /** The tag that names the client on each meter. */
  public static final String CLIENT_TAG = "client";

  /** The name the shared pool's meters carry in their {@code client} tag. */
  public static final String SHARED_POOL_TAG = "_shared";

  /** The start of the logger name of each client's call log, followed by the client's name. */
  public static final String CALL_LOGGER_PREFIX = "stratum.http-client.";

  /** The header that carries a client's API key when it names none. */
  public static final String DEFAULT_API_KEY_HEADER = "X-Api-Key";

  /**
   * The headers never accepted in {@code headers} or {@code copy-headers}, since they carry a
   * user's identity or a credential (ADR 0011, decision 3.4). A client's API key header is refused
   * too.
   */
  public static final List<String> REFUSED_HEADERS =
      List.of("Authorization", "Cookie", "Set-Cookie", "Proxy-Authorization");

  /** Stratum's default for the most of a request body an interceptor may read: 1 MiB. */
  public static final DataSize DEFAULT_INTERCEPTOR_BODY_LIMIT = DataSize.ofMegabytes(1);

  /** The largest {@code interceptor-body-limit} a client may set: 1 GiB. */
  public static final DataSize MAX_INTERCEPTOR_BODY_LIMIT = DataSize.ofGigabytes(1);

  /** Stratum's default for the most of an answer's body a client reads: 32 MiB. */
  public static final DataSize DEFAULT_RESPONSE_BODY_LIMIT = DataSize.ofMegabytes(32);

  /** The largest {@code response-body-limit} a client may set: 1 GiB. */
  public static final DataSize MAX_RESPONSE_BODY_LIMIT = DataSize.ofGigabytes(1);

  /** The {@code response-body-limit} that reads an answer's body whole, however large: -1. */
  public static final DataSize NO_RESPONSE_BODY_LIMIT = DataSize.ofBytes(-1);

  /**
   * The attribute that marks a call whose body an interceptor may read: it is not streamed, so it
   * can be written a second time.
   */
  public static final String BODY_READABLE = PREFIX + ".body-readable";

  /**
   * Returns the key that gives one code its HTTP status in the web starter's map, with the code in
   * brackets so that it is kept as written.
   *
   * @param code the code, such as {@code SX-HTTPC-0001}
   * @return the key, such as {@code stratum.web.error.statuses[SX-HTTPC-0001]}
   */
  public static String errorStatus(String code) {
    return WEB_ERROR_STATUSES + "[" + code + "]";
  }
}
