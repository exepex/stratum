package com.exepex.stratum.observability.testsupport;

import com.exepex.stratum.observability.ObservabilityProperties;
import com.exepex.stratum.observability.ObservabilityPropertiesCustomizer;

/** A service's Java configuration that lets its HTTP client metrics keep only a few hosts. */
public class FewHostsObservabilitySettings implements ObservabilityPropertiesCustomizer {

  /** The most hosts this configuration keeps. */
  public static final int MAX_CLIENT_NAMES = 2;

  @Override
  public ObservabilityProperties customize(ObservabilityProperties properties) {
    return properties.toBuilder()
        .metrics(properties.metrics().toBuilder().maxClientNames(MAX_CLIENT_NAMES).build())
        .build();
  }
}
