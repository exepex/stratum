package com.exepex.stratum.web.testsupport;

import com.exepex.stratum.web.WebProperties;
import java.io.IOException;
import java.util.Map;
import lombok.experimental.UtilityClass;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/** Settings and IDs shared by the web module's tests. */
@UtilityClass
public class WebTestData {

  /** A trace ID as tracing makes one. */
  public static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  /** A correlation ID as a caller sends one. */
  public static final String CORRELATION_ID = "checkout-777";

  /** How every message about a broken setting starts. */
  public static final String INVALID_SETTINGS = "SX-CORE-1003: Invalid settings: ";

  /** The name of the tests that show a service's Java configuration wins. */
  public static final String JAVA_CONFIGURATION_WINS =
      "a customizer listed in spring.factories wins over the files";

  /**
   * Returns web settings whose error statuses map each code to its status.
   *
   * @param statusesByCode the HTTP status of each code
   * @return the settings
   */
  public static WebProperties withErrorStatuses(Map<String, Integer> statusesByCode) {
    return defaults()
        .error(WebProperties.ErrorSettings.builder().statuses(statusesByCode).build())
        .build();
  }

  /**
   * Returns a builder holding the web settings a service gets when it sets nothing.
   *
   * @return the builder
   */
  public static WebProperties.WebPropertiesBuilder defaults() {
    return WebProperties.resolve(new MockEnvironment()).toBuilder();
  }

  /**
   * Returns an environment holding the settings of web-yaml.yml, as a service's file does.
   *
   * @return the environment
   * @throws IOException if the file cannot be read
   */
  public static StandardEnvironment yamlEnvironment() throws IOException {
    var environment = new StandardEnvironment();
    new YamlPropertySourceLoader()
        .load("web-yaml", new ClassPathResource("web-yaml.yml"))
        .forEach(environment.getPropertySources()::addFirst);
    return environment;
  }
}
