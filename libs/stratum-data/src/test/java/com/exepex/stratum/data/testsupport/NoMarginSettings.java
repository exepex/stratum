package com.exepex.stratum.data.testsupport;

import com.exepex.stratum.data.DataProperties;
import com.exepex.stratum.data.DataPropertiesCustomizer;
import java.time.Duration;

/** A service's Java settings that remove the deadline margin, which win over the files. */
public class NoMarginSettings implements DataPropertiesCustomizer {

  @Override
  public DataProperties customize(DataProperties properties) {
    return properties.toBuilder().deadlineMargin(Duration.ZERO).build();
  }
}
