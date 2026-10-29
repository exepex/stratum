package com.exepex.stratum.observability.testsupport;

import com.exepex.stratum.observability.ObservabilityProperties;
import com.exepex.stratum.observability.ObservabilityPropertiesCustomizer;
import java.util.List;

/** A service's Java configuration that exposes the prometheus endpoint and keeps no histograms. */
public class PrometheusEndpointObservabilitySettings implements ObservabilityPropertiesCustomizer {

  /** The endpoints this configuration exposes. */
  public static final List<String> ENDPOINTS = List.of("health", "prometheus");

  @Override
  public ObservabilityProperties customize(ObservabilityProperties properties) {
    return properties.toBuilder().exposedEndpoints(ENDPOINTS).histograms(false).build();
  }
}
