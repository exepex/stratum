package com.exepex.stratum.httpclient.testsupport;

import com.exepex.stratum.httpclient.BackoffPolicy;
import com.exepex.stratum.httpclient.ClientCircuitBreaker;
import com.exepex.stratum.httpclient.ClientRetry;
import com.exepex.stratum.httpclient.HttpClientConstants;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import lombok.experimental.UtilityClass;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** Names and values shared by the HTTP client module's tests. */
@UtilityClass
public class HttpClientTestData {

  /** The client the tests configure. */
  public static final String INVENTORY = "inventory";

  /** The prefix of the inventory client's own settings. */
  public static final String INVENTORY_SETTINGS = HttpClientConstants.CLIENTS + "." + INVENTORY;

  /** The prefix of the inventory client's auth settings. */
  public static final String INVENTORY_AUTH = INVENTORY_SETTINGS + ".auth";

  /** The prefix of the inventory client's retry settings. */
  public static final String INVENTORY_RETRY = INVENTORY_SETTINGS + ".retry";

  /** The prefix of the inventory client's circuit breaker settings. */
  public static final String INVENTORY_BREAKER = INVENTORY_SETTINGS + ".circuit-breaker";

  /** The prefix of the shared retry settings. */
  public static final String DEFAULTS_RETRY = HttpClientConstants.DEFAULTS + ".retry";

  /** The prefix of the shared circuit breaker settings. */
  public static final String DEFAULTS_BREAKER = HttpClientConstants.DEFAULTS + ".circuit-breaker";

  /**
   * Stratum's retry settings: 3 retries, an exponential back-off from 200ms that doubles, and no
   * max-delay of its own.
   */
  public static final ClientRetry STRATUM_RETRY =
      ClientRetry.builder()
          .maxRetries(HttpClientConstants.DEFAULT_RETRIES)
          .backoffPolicy(BackoffPolicy.EXPONENTIAL)
          .delay(HttpClientConstants.DEFAULT_RETRY_DELAY)
          .multiplier(HttpClientConstants.DEFAULT_MULTIPLIER)
          .build();

  /**
   * Stratum's circuit breaker settings, which are Resilience4j's own defaults: on, 50%, 100
   * attempts, at least 100, 60s open, 10 trial calls, and no slow-call rule.
   */
  public static final ClientCircuitBreaker STRATUM_CIRCUIT_BREAKER =
      ClientCircuitBreaker.builder()
          .enabled(true)
          .failureRateThreshold(HttpClientConstants.DEFAULT_FAILURE_RATE_THRESHOLD)
          .slidingWindowSize(HttpClientConstants.DEFAULT_SLIDING_WINDOW_SIZE)
          .minimumNumberOfCalls(HttpClientConstants.DEFAULT_MINIMUM_NUMBER_OF_CALLS)
          .waitDurationInOpenState(HttpClientConstants.DEFAULT_WAIT_IN_OPEN_STATE)
          .permittedNumberOfCallsInHalfOpenState(HttpClientConstants.DEFAULT_HALF_OPEN_CALLS)
          .build();

  /** The partner client, which {@code http-client.yml} gives Basic credentials. */
  public static final String PARTNER = "partner";

  /**
   * A stand-in for a secret, which no startup message may show. It is not a real credential: tests
   * check only that it is passed on or hidden.
   */
  public static final String SECRET = "not-a-real-secret-4f2a";

  /** The inventory client's address. */
  public static final String INVENTORY_URL = "http://inventory:8080";

  /** Two generated interfaces of one partner, as in {@code http-client.yml}. */
  public static final List<String> PARTNER_INTERFACES =
      List.of("com.partner.OrdersApi", "com.partner.CustomersApi");

  /** A timeout a slow report endpoint needs. */
  public static final Duration REPORT_TIMEOUT = Duration.ofSeconds(20);

  /** A total timeout a slow report endpoint needs, also set in {@code http-client.yml}. */
  public static final Duration REPORT_TOTAL_TIMEOUT = Duration.ofSeconds(25);

  /** The inventory client's own retries in {@code http-client.yml}. */
  public static final int FLAKY_RETRIES = 5;

  /** The inventory client's own retry settings in {@code http-client.yml}. */
  public static final ClientRetry FLAKY_RETRY =
      ClientRetry.builder().maxRetries(FLAKY_RETRIES).backoffPolicy(BackoffPolicy.FIXED).build();

  /** The shared retry delay in {@code http-client.yml}. */
  public static final Duration SLOW_RETRY_DELAY = Duration.ofSeconds(1);

  /** The shared time an open breaker stays open in {@code http-client.yml}. */
  public static final Duration SHORT_OPEN_STATE = Duration.ofSeconds(30);

  /** The inventory client's own failure rate, in percent, in {@code http-client.yml}. */
  public static final float STRICT_FAILURE_RATE = 10;

  /** The inventory client's own slow-call duration in {@code http-client.yml}. */
  public static final Duration SLOW_CALL = Duration.ofSeconds(2);

  /** The inventory client's own circuit breaker settings in {@code http-client.yml}. */
  public static final ClientCircuitBreaker STRICT_BREAKER =
      ClientCircuitBreaker.builder()
          .enabled(false)
          .failureRateThreshold(STRICT_FAILURE_RATE)
          .slowCallDurationThreshold(SLOW_CALL)
          .build();

  /** The shared timeout in {@code http-client.yml}. */
  public static final Duration SHARED_TIMEOUT = Duration.ofSeconds(3);

  /** The inventory client's own timeout in {@code http-client.yml}. */
  public static final Duration INVENTORY_TIMEOUT = Duration.ofSeconds(10);

  /** A shared pool size other than Stratum's default. */
  public static final int SHARED_MAX_CONNECTIONS = 20;

  /** The start of every message about settings that break their rules. */
  public static final String INVALID_SETTINGS = "SX-CORE-1003: Invalid settings: ";

  /** The name of the tests that show a service's Java configuration wins. */
  public static final String JAVA_CONFIGURATION_WINS =
      "a customizer listed in spring.factories wins over the files";

  /**
   * Returns an environment that holds {@code http-client.yml}: a shared timeout of 3s, retry delay
   * of 1s and open state of 30s, and the inventory client's address with its own timeout of 10s,
   * total timeout of 25s, {@link #FLAKY_RETRY}, {@link #STRICT_BREAKER} and two generated
   * interfaces.
   *
   * @return the environment
   * @throws IOException if the file cannot be read
   */
  public static StandardEnvironment yamlEnvironment() throws IOException {
    var environment = new StandardEnvironment();
    new YamlPropertySourceLoader()
        .load("http-client-yaml", new ClassPathResource("http-client.yml"))
        .forEach(environment.getPropertySources()::addFirst);
    return environment;
  }
}
