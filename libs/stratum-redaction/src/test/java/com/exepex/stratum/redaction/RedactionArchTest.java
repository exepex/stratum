package com.exepex.stratum.redaction;

import com.exepex.stratum.test.StarterTestSupport;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The redaction library follows Stratum's architecture rules")
class RedactionArchTest {

  @Test
  @DisplayName("every type a service can reach carries the label, and there is no package cycle")
  void libraryArchitecture() {
    // No type is API: only Stratum's starters use these rules.
    StarterTestSupport.checkLibraryArchitecture(MaskedText.class, Set.of());
  }
}
