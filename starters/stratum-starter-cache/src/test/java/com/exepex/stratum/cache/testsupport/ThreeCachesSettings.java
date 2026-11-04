package com.exepex.stratum.cache.testsupport;

import com.exepex.stratum.cache.StratumCacheProperties;
import com.exepex.stratum.cache.StratumCachePropertiesCustomizer;

/** A customizer that admits only three caches, whatever the files say, for the customizer test. */
public class ThreeCachesSettings implements StratumCachePropertiesCustomizer {

  /** How many caches the customizer admits. */
  public static final int MAX_CACHES = 3;

  @Override
  public StratumCacheProperties customize(StratumCacheProperties properties) {
    return properties.toBuilder().maxCaches(MAX_CACHES).build();
  }
}
