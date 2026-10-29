package com.exepex.stratum.logging.testsupport;

import com.exepex.stratum.logging.LoggingProperties;
import com.exepex.stratum.logging.LoggingPropertiesCustomizer;
import org.springframework.util.unit.DataSize;

/** A service's Java configuration that sets a field length above the 1MB limit. */
public class OversizedFieldLoggingSettings implements LoggingPropertiesCustomizer {

  @Override
  public LoggingProperties customize(LoggingProperties properties) {
    return properties.toBuilder().maxFieldLength(DataSize.ofMegabytes(2)).build();
  }
}
