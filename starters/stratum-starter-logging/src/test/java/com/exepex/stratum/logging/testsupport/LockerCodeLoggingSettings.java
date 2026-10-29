package com.exepex.stratum.logging.testsupport;

import com.exepex.stratum.logging.LoggingProperties;
import com.exepex.stratum.logging.LoggingProperties.LogFormat;
import com.exepex.stratum.logging.LoggingPropertiesCustomizer;
import java.util.List;

/** A service's Java configuration: ECS lines, with {@code locker-code} masked. */
public class LockerCodeLoggingSettings implements LoggingPropertiesCustomizer {

  /** The extra name this configuration masks. */
  public static final String LOCKER_CODE = "locker-code";

  @Override
  public LoggingProperties customize(LoggingProperties properties) {
    return properties.toBuilder()
        .format(LogFormat.ECS)
        .redaction(properties.redaction().toBuilder().extraKeys(List.of(LOCKER_CODE)).build())
        .build();
  }
}
