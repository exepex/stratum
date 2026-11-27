package com.exepex.stratum.resilience;

import com.exepex.stratum.test.StarterTestSupport;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The resilience starter follows Stratum's architecture rules")
class ResilienceArchTest {

  /**
   * The resilience starter's types, top-level or nested, that services may use: the settings
   * record, its nested settings and builders, and its customizer. Every other type carries the
   * label.
   */
  private static final Set<Class<?>> API_TYPES =
      Set.of(
          ResilienceProperties.class,
          ResilienceProperties.ResiliencePropertiesBuilder.class,
          ResilienceProperties.IncomingSettings.class,
          ResilienceProperties.IncomingSettings.IncomingSettingsBuilder.class,
          ResilienceProperties.RouteLimit.class,
          ResilienceProperties.RouteLimit.RouteLimitBuilder.class,
          ResiliencePropertiesCustomizer.class);

  @Test
  @DisplayName(
      "its autoconfigure package only declares beans, it uses no other starter, has no cycles and"
          + " every public type is internal or listed")
  void followsStarterArchitecture() {
    StarterTestSupport.checkStarterArchitecture(
        ResilienceConstants.class, API_TYPES, "com.exepex.stratum.web");
  }
}
