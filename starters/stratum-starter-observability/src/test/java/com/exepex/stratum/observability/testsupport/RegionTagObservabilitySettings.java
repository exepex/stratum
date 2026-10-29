package com.exepex.stratum.observability.testsupport;

import com.exepex.stratum.observability.ObservabilityProperties;
import com.exepex.stratum.observability.ObservabilityPropertiesCustomizer;
import java.util.List;

/** A service's Java configuration that refuses the {@code regionId} tag instead of its files'. */
public class RegionTagObservabilitySettings implements ObservabilityPropertiesCustomizer {

  /** The tag name this configuration refuses. */
  public static final String REGION_TAG = "regionId";

  @Override
  public ObservabilityProperties customize(ObservabilityProperties properties) {
    return properties.toBuilder()
        .tags(properties.tags().toBuilder().extraForbidden(List.of(REGION_TAG)).build())
        .build();
  }
}
