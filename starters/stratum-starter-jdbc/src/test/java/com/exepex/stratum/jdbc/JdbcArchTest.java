package com.exepex.stratum.jdbc;

import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.test.StarterTestSupport;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The JDBC starter follows Stratum's architecture rules")
class JdbcArchTest {

  @Test
  @DisplayName(
      "its autoconfigure package only declares beans, it uses only the shared data library and"
          + " core, has no cycles and every public type is internal")
  void followsStarterArchitecture() throws ClassNotFoundException {
    // The starter's only class is in its autoconfigure package, so its top package is named by
    // its package-info, which is compiled because it carries @NullMarked. No API types: the
    // starter's API, such as JdbcBoundaryPostProcessor, is in stratum-data.
    StarterTestSupport.checkStarterArchitecture(
        Class.forName(getClass().getPackageName() + ".package-info"),
        Set.of(),
        DataConstants.class.getPackageName());
  }
}
