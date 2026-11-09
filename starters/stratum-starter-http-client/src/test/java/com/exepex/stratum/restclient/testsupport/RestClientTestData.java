package com.exepex.stratum.restclient.testsupport;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.autoconfigure.RestClientAutoConfiguration;
import java.time.Duration;
import lombok.experimental.UtilityClass;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientPropertiesAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Names, values and set-up shared by the blocking HTTP client starter's tests. */
@UtilityClass
public class RestClientTestData {

  /** The client most tests configure. */
  public static final String INVENTORY = "inventory";

  /** A second client. */
  public static final String PRICING = "pricing";

  /** The prefix of the inventory client's own settings. */
  public static final String INVENTORY_SETTINGS = HttpClientConstants.CLIENTS + "." + INVENTORY;

  /** The prefix of the pricing client's own settings. */
  public static final String PRICING_SETTINGS = HttpClientConstants.CLIENTS + "." + PRICING;

  /** The key of the inventory client's address. */
  public static final String INVENTORY_URL = INVENTORY_SETTINGS + ".url";

  /** The key of the pricing client's address. */
  public static final String PRICING_URL = PRICING_SETTINGS + ".url";

  /** An address no test calls, for tests that only start the application. */
  public static final String UNUSED_URL = "http://127.0.0.1:9";

  /**
   * A short timeout or wait, far below Stratum's defaults, so that a test sees which setting failed
   * the call and stays fast.
   */
  public static final Duration SHORT = Duration.ofMillis(300);

  /** Longer than {@link #SHORT} and far below the next default, for a call that fails fast. */
  public static final Duration FAILS_WITHIN = Duration.ofMillis(1500);

  /** The longest a test waits for a downstream to see what it expects. */
  public static final Duration WAIT = Duration.ofSeconds(10);

  /**
   * Returns a runner with the starter and the Spring Boot auto-configurations it builds on.
   *
   * @return the runner
   */
  public static ApplicationContextRunner contextRunner() {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                RestClientAutoConfiguration.class,
                HttpClientAutoConfiguration.class,
                ImperativeHttpClientAutoConfiguration.class,
                HttpServiceClientPropertiesAutoConfiguration.class,
                org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration.class,
                HttpServiceClientAutoConfiguration.class));
  }

  /**
   * Returns the setting {@code key=value}, for a runner or a test's properties.
   *
   * @param key the key
   * @param value the value
   * @return the setting
   */
  public static String setting(String key, Object value) {
    return key + "=" + value;
  }
}
