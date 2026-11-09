package com.exepex.stratum.restclient.testsupport;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.restclient.ClientConnectionPools;
import com.exepex.stratum.restclient.autoconfigure.RestClientAutoConfiguration;
import com.exepex.stratum.restclient.testsupport.clients.ClientsApplication;
import java.time.Duration;
import lombok.experimental.UtilityClass;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientPropertiesAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Names, values and set-up shared by the blocking HTTP client starter's tests. */
@UtilityClass
public class RestClientTestData {

  /** The end of the key of a client's address. */
  private static final String URL = ".url";

  /** The client most tests configure. */
  public static final String INVENTORY = "inventory";

  /** A second client. */
  public static final String PRICING = "pricing";

  /** A client made only of a generated interface that its settings name. */
  public static final String PARTNER = "partner";

  /** The prefix of the partner client's own settings. */
  public static final String PARTNER_SETTINGS = HttpClientConstants.CLIENTS + "." + PARTNER;

  /** The key of the partner client's address. */
  public static final String PARTNER_URL = PARTNER_SETTINGS + URL;

  /** The key of the first generated interface of the partner client. */
  public static final String PARTNER_INTERFACE = PARTNER_SETTINGS + ".interfaces[0]";

  /** A client that moves files, for the streaming tests. */
  public static final String FILES = "files";

  /** The prefix of the files client's own settings. */
  public static final String FILES_SETTINGS = HttpClientConstants.CLIENTS + "." + FILES;

  /** A client whose downstream answers with error bodies, for the error tests. */
  public static final String ORDERS = "orders";

  /** The key of the orders client's address. */
  public static final String ORDERS_URL = HttpClientConstants.CLIENTS + "." + ORDERS + URL;

  /** The prefix of the inventory client's own settings. */
  public static final String INVENTORY_SETTINGS = HttpClientConstants.CLIENTS + "." + INVENTORY;

  /** The prefix of the pricing client's own settings. */
  public static final String PRICING_SETTINGS = HttpClientConstants.CLIENTS + "." + PRICING;

  /** The key of the inventory client's address. */
  public static final String INVENTORY_URL = INVENTORY_SETTINGS + URL;

  /** The key of the pricing client's address. */
  public static final String PRICING_URL = PRICING_SETTINGS + URL;

  /**
   * A stand-in for a secret: not a real credential, as tests only check that it is passed on or
   * hidden.
   */
  public static final String SECRET = "not-a-real-secret-4f2a";

  /**
   * The setting that turns Stratum's retries off for every client, for a test of one attempt of a
   * call, such as Apache's own resend or one timeout.
   */
  public static final String NO_RETRIES = HttpClientConstants.DEFAULTS + ".retries=0";

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
   * Returns pools of the kind a service or a company framework declares as its own bean, with
   * Stratum's defaults and no SSL bundles.
   *
   * @return the pools
   */
  public static ClientConnectionPools ownPools() {
    return new ClientConnectionPools(
        HttpClientProperties.builder().build().defaults(),
        new StaticListableBeanFactory().getBeanProvider(SslBundles.class));
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

  /**
   * Points both clients at one server, for a test that calls only one of them.
   *
   * @param registry the test's properties
   * @param downstream the server
   */
  public static void pointClientsAt(
      DynamicPropertyRegistry registry, ScriptedDownstream downstream) {
    registry.add(INVENTORY_URL, downstream::url);
    registry.add(PRICING_URL, downstream::url);
  }

  /**
   * Starts the test application, for a test of what happens at startup or shutdown (ST-TEST-31).
   *
   * @param settings the application's settings, as {@code key=value}
   * @return the running application, which the caller closes
   */
  public static ConfigurableApplicationContext startApplication(String... settings) {
    return new SpringApplicationBuilder(ClientsApplication.class)
        .web(WebApplicationType.NONE)
        .properties(settings)
        .run();
  }
}
