package com.exepex.stratum.core.properties.testsupport;

import com.exepex.stratum.core.properties.StratumPropertiesCustomizer;
import com.exepex.stratum.core.properties.fixture.GreetingAutoConfiguration.GreetingProperties;
import java.time.Duration;
import lombok.experimental.UtilityClass;

/** Settings and customizers of the test module, shared by the properties tests. */
@UtilityClass
public class GreetingTestData {

  /** The test module's default timeout, as its properties type declares it. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

  /** The timeout that the files under {@code src/test/resources/greeting} set. */
  public static final Duration FILE_TIMEOUT = Duration.ofSeconds(5);

  /** The timeout that {@link #javaTimeout()} sets. */
  public static final Duration JAVA_TIMEOUT = Duration.ofSeconds(9);

  /**
   * Returns the test module's settings with every default.
   *
   * @return a builder with message {@code Hello} and a 2-second timeout
   */
  public static GreetingProperties.GreetingPropertiesBuilder defaults() {
    return GreetingProperties.builder().message("Hello").timeout(DEFAULT_TIMEOUT);
  }

  /**
   * Returns a customizer that sets the message and keeps the timeout.
   *
   * @param message the message to set
   * @return the customizer
   */
  public static StratumPropertiesCustomizer<GreetingProperties> javaMessage(String message) {
    return properties -> properties.toBuilder().message(message).build();
  }

  /**
   * Returns a customizer that sets the timeout to {@link #JAVA_TIMEOUT} and keeps the message.
   *
   * @return the customizer
   */
  public static StratumPropertiesCustomizer<GreetingProperties> javaTimeout() {
    return properties -> properties.toBuilder().timeout(JAVA_TIMEOUT).build();
  }

  /**
   * Returns a customizer for another module, whose settings are a plain {@code String}. It casts
   * what it receives, so it fails if it is ever given the test module's properties.
   *
   * @return the customizer
   */
  public static StratumPropertiesCustomizer<String> otherModule() {
    return properties -> properties.strip();
  }
}
