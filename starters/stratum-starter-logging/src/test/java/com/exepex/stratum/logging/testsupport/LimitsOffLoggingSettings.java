package com.exepex.stratum.logging.testsupport;

import com.exepex.stratum.logging.LoggingProperties;
import com.exepex.stratum.logging.LoggingPropertiesCustomizer;

/** A service's Java configuration that turns the volume limits off. */
public class LimitsOffLoggingSettings implements LoggingPropertiesCustomizer {

  @Override
  public LoggingProperties customize(LoggingProperties properties) {
    return properties.toBuilder()
        .volume(properties.volume().toBuilder().enabled(false).build())
        .build();
  }
}
