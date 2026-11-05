package com.exepex.stratum.cache;

import com.exepex.stratum.test.StarterTestSupport;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The cache starter follows Stratum's architecture rules")
class CacheArchTest {

  /**
   * The cache starter's types, top-level or nested, that services may use: the settings record and
   * its customizer, as every module's, the settings for one cache and for stored values. Every
   * other type carries the label.
   */
  private static final Set<Class<?>> API_TYPES =
      Set.of(
          StratumCacheProperties.class,
          StratumCacheProperties.StratumCachePropertiesBuilder.class,
          StratumCacheProperties.CacheSettings.class,
          StratumCacheProperties.Serialization.class,
          StratumCacheProperties.CaffeineSettings.class,
          StratumCacheProperties.CacheSettings.CacheSettingsBuilder.class,
          StratumCachePropertiesCustomizer.class);

  @Test
  @DisplayName(
      "its autoconfigure package only declares beans, it uses no other starter, has no cycles and"
          + " every public type is internal or listed")
  void followsStarterArchitecture() {
    StarterTestSupport.checkStarterArchitecture(CacheConstants.class, API_TYPES);
  }
}
