package com.exepex.stratum.data;

import com.exepex.stratum.test.StarterTestSupport;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The data starter follows Stratum's architecture rules")
class DataArchTest {

  /**
   * The data starter's types, top-level or nested, that services may use: the post-processor they
   * replace to skip a data source, and the settings record, its builder and its customizer, as
   * every module's. Every other type carries the label.
   */
  private static final Set<Class<?>> API_TYPES =
      Set.of(
          JdbcBoundaryPostProcessor.class,
          DataProperties.class,
          DataProperties.DataPropertiesBuilder.class,
          DataPropertiesCustomizer.class);

  @Test
  @DisplayName(
      "its autoconfigure package only declares beans, it uses no other starter, has no cycles and"
          + " every public type is internal or on its API list")
  void followsStarterArchitecture() {
    StarterTestSupport.checkStarterArchitecture(DataConstants.class, API_TYPES);
  }
}
