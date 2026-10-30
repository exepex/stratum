package com.exepex.stratum.webmvc;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.exepex.stratum.test.StarterTestSupport;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@DisplayName("The web MVC starter follows Stratum's architecture rules")
class WebMvcArchTest {

  /** The package that may only create and wire beans (ST-ENG-23). */
  private static final String AUTOCONFIGURE_PACKAGE = "com.exepex.stratum.webmvc.autoconfigure";

  @Test
  @DisplayName("The autoconfigure package holds only configuration classes")
  void autoconfigurePackageHoldsOnlyConfigurationClasses() {
    checkStarterClasses(
        classes()
            .that()
            .resideInAPackage(AUTOCONFIGURE_PACKAGE)
            .and()
            .doNotHaveSimpleName("package-info")
            .should()
            .beMetaAnnotatedWith(Configuration.class));
  }

  @Test
  @DisplayName("Classes in the autoconfigure package only declare bean methods")
  void autoconfigurePackageOnlyDeclaresBeanMethods() {
    checkStarterClasses(
        methods()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage(AUTOCONFIGURE_PACKAGE)
            .should()
            .beAnnotatedWith(Bean.class));
  }

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
                            "com.exepex.stratum.webmvc..",
                            "com.exepex.stratum.web..",
                            "com.exepex.stratum.core.."))));
  }

  @Test
  @DisplayName("The starter's packages have no cycles")
  void packagesHaveNoCycles() {
    checkStarterClasses(slices().matching("com.exepex.stratum.(**)").should().beFreeOfCycles());
  }

  @Test
  @DisplayName("Every class a component scan picks up carries the module switch")
  void safeToScan() {
    StarterTestSupport.checkSafeToScan(StratumExceptionHandler.class);
  }

  /** Checks the rule against the starter's own classes, without its tests. */
  private void checkStarterClasses(ArchRule rule) {
    rule.check(
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.exepex.stratum.webmvc"));
  }
}
