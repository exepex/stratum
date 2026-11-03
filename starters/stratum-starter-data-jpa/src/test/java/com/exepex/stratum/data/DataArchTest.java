package com.exepex.stratum.data;

import com.exepex.stratum.test.StarterTestSupport;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The data starter follows Stratum's architecture rules")
class DataArchTest {

  @Test
  @DisplayName(
      "its autoconfigure package only declares beans, it uses no other starter, has no cycles and"
          + " every public type is internal or on its API list")
  void followsStarterArchitecture() {
    StarterTestSupport.checkStarterArchitecture(
        DataConstants.class, Set.of(JdbcBoundaryPostProcessor.class));
  }
}
