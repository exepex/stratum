package com.exepex.stratum.web.testsupport;

import com.exepex.stratum.web.WebProperties;
import java.util.Map;
import lombok.experimental.UtilityClass;
import org.springframework.mock.env.MockEnvironment;

/** Settings and IDs shared by the web module's tests. */
@UtilityClass
public class WebTestData {

  /** A trace ID as tracing makes one. */
  public static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  /** A correlation ID as a caller sends one. */
  public static final String CORRELATION_ID = "checkout-777";

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
}
