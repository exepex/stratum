package com.exepex.stratum.core.properties.testsupport;

import com.exepex.stratum.core.properties.StratumPropertiesCustomizer;
import com.exepex.stratum.core.properties.fixture.GreetingProperties;
import java.time.Duration;

/** Settings and customizers of the test module, shared by the properties tests. */
public final class GreetingTestData {

  /** The test module's default timeout, as its properties type declares it. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

  /** The timeout that the files under {@code src/test/resources/greeting} set. */
  public static final Duration FILE_TIMEOUT = Duration.ofSeconds(5);

  /** The timeout that {@link #javaTimeout()} sets. */
  public static final Duration JAVA_TIMEOUT = Duration.ofSeconds(9);

  /** Not instantiated: a holder of shared test data. */
  private GreetingTestData() {}

  /**
   * Returns the test module's settings with every default.
   *
   * @return settings with message {@code Hello} and a 2-second timeout
   */
  public static GreetingProperties defaults() {
    return new GreetingProperties("Hello", DEFAULT_TIMEOUT);
  }

  /**
   * Returns a customizer that sets the message and keeps the timeout.
   *
   * @param message the message to set
   * @return the customizer
   */
  public static StratumPropertiesCustomizer<GreetingProperties> javaMessage(String message) {
    return properties -> new GreetingProperties(message, properties.timeout());
  }

  /**
   * Returns a customizer that sets the timeout to {@link #JAVA_TIMEOUT} and keeps the message.
   *
   * @return the customizer
   */
  public static StratumPropertiesCustomizer<GreetingProperties> javaTimeout() {
    return properties -> new GreetingProperties(properties.message(), JAVA_TIMEOUT);
  }
}
