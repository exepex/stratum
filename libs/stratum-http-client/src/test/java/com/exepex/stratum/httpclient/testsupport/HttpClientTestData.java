package com.exepex.stratum.httpclient.testsupport;

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
   * Returns an environment that holds {@code http-client.yml}: a shared timeout of 3s and the
   * inventory client's address with its own timeout of 10s and two generated interfaces.
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
