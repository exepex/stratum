package com.exepex.stratum.core;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Stratum core follows Stratum's architecture rules")
class CoreArchTest {

  @Test
  @DisplayName("Core's packages have no cycles")
  void packagesHaveNoCycles() {
    slices()
        .matching("com.exepex.stratum.(**)")
        .should()
        .beFreeOfCycles()
        .check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.exepex.stratum.core"));
  }
}
