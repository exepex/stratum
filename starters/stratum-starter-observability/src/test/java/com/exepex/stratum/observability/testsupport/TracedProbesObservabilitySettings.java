package com.exepex.stratum.observability.testsupport;

import com.exepex.stratum.observability.ObservabilityProperties;
import com.exepex.stratum.observability.ObservabilityPropertiesCustomizer;

/** A service's Java configuration that traces health probes, whatever its files say. */
public class TracedProbesObservabilitySettings implements ObservabilityPropertiesCustomizer {

  @Override
  public ObservabilityProperties customize(ObservabilityProperties properties) {
    return properties.toBuilder()
        .tracing(ObservabilityProperties.Tracing.builder().healthProbes(true).build())
        .build();
  }
}
