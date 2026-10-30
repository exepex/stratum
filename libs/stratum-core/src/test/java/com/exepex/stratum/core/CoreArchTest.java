package com.exepex.stratum.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

@DisplayName("Stratum core follows Stratum's architecture rules")
class CoreArchTest {

  @Test
  @DisplayName("Core's packages have no cycles")
  void packagesHaveNoCycles() {
    checkCoreClasses(slices().matching("com.exepex.stratum.(**)").should().beFreeOfCycles());
  }

  @Test
  @DisplayName("No core class is picked up by a service's component scan (ST-ENG-20)")
  void noClassIsPickedUpByComponentScan() {
    checkCoreClasses(noClasses().should().beMetaAnnotatedWith(Component.class));
  }

  /** Checks the rule against core's own classes, without its tests. */
  private void checkCoreClasses(ArchRule rule) {
    rule.check(
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.exepex.stratum.core"));
  }
}
