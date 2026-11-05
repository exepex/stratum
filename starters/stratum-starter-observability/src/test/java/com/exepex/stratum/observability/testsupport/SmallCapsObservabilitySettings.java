package com.exepex.stratum.observability.testsupport;

import com.exepex.stratum.observability.ObservabilityProperties;
import com.exepex.stratum.observability.ObservabilityPropertiesCustomizer;

/** A service's Java configuration that sets small meter caps, so a test can fill them. */
public class SmallCapsObservabilitySettings implements ObservabilityPropertiesCustomizer {

  /** The most meters this configuration keeps. */
  public static final int MAX_METERS = 7;

  /** The most hosts this configuration keeps. */
  public static final int MAX_CLIENT_NAMES = 2;

  @Override
  public ObservabilityProperties customize(ObservabilityProperties properties) {
    return properties.toBuilder()
        .metrics(
            properties.metrics().toBuilder()
                .maxMeters(MAX_METERS)
                .maxClientNames(MAX_CLIENT_NAMES)
                .build())
        .build();
  }
}
