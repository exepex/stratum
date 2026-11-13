package com.exepex.stratum.data;

import com.exepex.stratum.test.StarterTestSupport;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The shared data library follows Stratum's architecture rules")
class DataArchTest {

  /**
   * The shared data library's types, top-level or nested, that services may use: the post-processor
   * they replace to skip a data source, and the settings record, its builder and its customizer, as
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
      "every class a component scan picks up carries the module switch, and no package cycle")
  void libraryArchitecture() {
    StarterTestSupport.checkLibraryArchitecture(DataConstants.class, API_TYPES);
  }
}
