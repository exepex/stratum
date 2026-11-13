package com.exepex.stratum.datajpa;

import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.test.StarterTestSupport;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The data JPA starter follows Stratum's architecture rules")
class DataJpaArchTest {

  @Test
  @DisplayName(
      "its autoconfigure package only declares beans, it uses only the shared data library and"
          + " core, has no cycles and every public type is internal")
  void followsStarterArchitecture() {
    // No API types: the starter's API, such as JdbcBoundaryPostProcessor, is in stratum-data.
    StarterTestSupport.checkStarterArchitecture(
        DataJpaConstants.class, Set.of(), DataConstants.class.getPackageName());
  }
}
