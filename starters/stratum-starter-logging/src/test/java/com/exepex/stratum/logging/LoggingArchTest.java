package com.exepex.stratum.logging;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The logging starter follows Stratum's architecture rules")
class LoggingArchTest {

  @Test
  @DisplayName("The starter uses no class of another starter")
  void usesNoClassOfAnotherStarter() {
    checkStarterClasses(
        classes()
            .should()
            .onlyDependOnClassesThat(
                resideOutsideOfPackage("com.exepex.stratum..")
                    .or(
                        resideInAnyPackage(
                            "com.exepex.stratum.logging..", "com.exepex.stratum.core.."))));
  }

  @Test
  @DisplayName("The starter's packages have no cycles")
  void packagesHaveNoCycles() {
    checkStarterClasses(slices().matching("com.exepex.stratum.(**)").should().beFreeOfCycles());
  }

  /** Checks the rule against the starter's own classes, without its tests. */
  private void checkStarterClasses(ArchRule rule) {
    rule.check(
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackagesOf(LoggingConstants.class));
  }
}
