package com.exepex.stratum.cache;

import com.exepex.stratum.test.StarterTestSupport;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The cache starter follows Stratum's architecture rules")
class CacheArchTest {

  @Test
  @DisplayName(
      "its autoconfigure package only declares beans, it uses no other starter, has no cycles and"
          + " every public type is internal")
  void followsStarterArchitecture() {
    StarterTestSupport.checkStarterArchitecture(CacheConstants.class, Set.of());
  }
}
