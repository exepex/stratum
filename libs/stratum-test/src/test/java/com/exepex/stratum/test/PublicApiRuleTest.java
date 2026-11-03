package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.ErrorDetail;
import com.exepex.stratum.core.error.ErrorReport;
import com.exepex.stratum.core.error.ExternalError;
import com.exepex.stratum.core.error.FailureWithErrorCode;
import com.exepex.stratum.core.error.Severity;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.core.executor.OneShotContextScheduledExecutorService;
import com.exepex.stratum.core.properties.StratumPropertiesCustomizer;
import com.exepex.stratum.test.fixture.BoundLeakingGreeter;
import com.exepex.stratum.test.fixture.ExtensibleGreeter;
import com.exepex.stratum.test.fixture.InheritingGreeter;
import com.exepex.stratum.test.fixture.InternalGreeterWithNestedTypes;
import com.exepex.stratum.test.fixture.InternalGreeting;
import com.exepex.stratum.test.fixture.InternalGreetingFailure;
import com.exepex.stratum.test.fixture.LeakingGreeter;
import com.exepex.stratum.test.fixture.ThrowingGreeter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PublicApiRule keeps Stratum's API apart from its internal types")
class PublicApiRuleTest {

  /** The top package of stratum-core, which cannot depend on stratum-test and is checked here. */
  private static final String CORE_PACKAGE = "com.exepex.stratum.core";

  /** The top package of stratum-test itself. */
  private static final String TEST_PACKAGE = "com.exepex.stratum.test";

  /** Checks the rule against the main classes of the packages, without their tests. */
  private EvaluationResult evaluateOwnClasses(ArchRule rule, String... packages) {
    return rule.evaluate(
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(packages));
  }

  @Nested
  @DisplayName("apiExposesOnlyApi")
  class ApiExposesOnlyApi {

    @Test
    @DisplayName("fail for an API type whose public method returns an internal type")
    void failForApiExposingInternalType() {
      var result =
          PublicApiRule.apiExposesOnlyApi()
              .evaluate(
                  new ClassFileImporter()
                      .importClasses(LeakingGreeter.class, InternalGreeting.class));

      assertThat(result.getFailureReport().getDetails())
          .anyMatch(detail -> detail.contains(InternalGreeting.class.getName()));
    }

    @Test
    @DisplayName("fail for an API type that names an internal type only in a type-parameter bound")
    void failForInternalTypeInBound() {
      var result =
          PublicApiRule.apiExposesOnlyApi()
              .evaluate(
                  new ClassFileImporter()
                      .importClasses(BoundLeakingGreeter.class, InternalGreeting.class));

      assertThat(result.getFailureReport().getDetails())
          .anyMatch(detail -> detail.contains("its type parameters"))
          .anyMatch(detail -> detail.contains("notify"));
    }

    @Test
    @DisplayName("fail for an API type that names an internal type only in a throws clause")
    void failForInternalTypeInThrowsClause() {
      var result =
          PublicApiRule.apiExposesOnlyApi()
              .evaluate(
                  new ClassFileImporter()
                      .importClasses(ThrowingGreeter.class, InternalGreetingFailure.class));

      assertThat(result.getFailureReport().getDetails())
          .anyMatch(detail -> detail.contains(InternalGreetingFailure.class.getName()));
    }

    @Test
    @DisplayName("fail for an API type that inherits a public method naming an internal type")
    void failForInheritedMemberNamingInternalType() {
      var result =
          PublicApiRule.apiExposesOnlyApi()
              .evaluate(
                  new ClassFileImporter()
                      .importClasses(InheritingGreeter.class, InternalGreeting.class));

      assertThat(result.getFailureReport().getDetails())
          .anyMatch(detail -> detail.startsWith(InheritingGreeter.class.getName() + " exposes"));
    }

    @Test
    @DisplayName("fail for a protected nested type of an API type that names an internal type")
    void failForProtectedNestedType() {
      var result =
          PublicApiRule.apiExposesOnlyApi()
              .evaluate(new ClassFileImporter().importPackagesOf(ExtensibleGreeter.class));

      assertThat(result.getFailureReport().getDetails())
          .anyMatch(
              detail ->
                  detail.startsWith(ExtensibleGreeter.class.getName() + "$Extension exposes"));
    }

    @Test
    @DisplayName("pass for stratum-core and stratum-test, which no other module's tests check")
    void passForCoreAndTestLibraries() {
      var result =
          evaluateOwnClasses(PublicApiRule.apiExposesOnlyApi(), CORE_PACKAGE, TEST_PACKAGE);

      assertThat(result.hasViolation()).isFalse();
    }
  }

  @Nested
  @DisplayName("labelledOrListed")
  class LabelledOrListed {

    /** The types of stratum-core that services may use, nested ones too; the rest are labelled. */
    private static final Set<Class<?>> CORE_API_TYPES =
        Set.of(
            StratumInternal.class,
            ConditionalOnStratumModule.class,
            ErrorCode.class,
            ErrorDetail.class,
            ErrorReport.class,
            ExternalError.class,
            ExternalError.ExternalErrorBuilder.class,
            FailureWithErrorCode.class,
            Severity.class,
            StratumException.class,
            OneShotContextScheduledExecutorService.class,
            StratumPropertiesCustomizer.class);

    /** The types of stratum-test that services may use, nested ones too; the rest are labelled. */
    private static final Set<Class<?>> TEST_API_TYPES =
        Set.of(PostgresContainerConfiguration.class);

    @Test
    @DisplayName("fail for a public class with no label that is not on the API list")
    void failForUnlabelledClassNotOnList() {
      var result =
          PublicApiRule.labelledOrListed(Set.of(InheritingGreeter.class))
              .evaluate(new ClassFileImporter().importClasses(LeakingGreeter.class));

      assertThat(result.getFailureReport().getDetails())
          .anyMatch(detail -> detail.contains(LeakingGreeter.class.getName()));
    }

    @Test
    @DisplayName("pass for a listed class, a labelled class and a package-private class")
    void passForListedLabelledAndPackagePrivateClasses() {
      var packagePrivateBase = InheritingGreeter.class.getSuperclass();
      var result =
          PublicApiRule.labelledOrListed(Set.of(InheritingGreeter.class))
              .evaluate(
                  new ClassFileImporter()
                      .importClasses(
                          InheritingGreeter.class, packagePrivateBase, InternalGreeting.class));

      assertThat(result.hasViolation()).isFalse();
    }

    @Test
    @DisplayName("fail for a public nested type with no label of its own, inside a labelled class")
    void failForUnlabelledNestedType() {
      var result =
          PublicApiRule.labelledOrListed(Set.of())
              .evaluate(
                  new ClassFileImporter()
                      .importClasses(InternalGreeterWithNestedTypes.class.getDeclaredClasses()));

      assertThat(result.getFailureReport().getDetails())
          .singleElement(STRING)
          .contains(InternalGreeterWithNestedTypes.Exposed.class.getName());
    }

    @Test
    @DisplayName("pass for a listed nested type and labelled, package-private and private ones")
    void passForListedLabelledAndHiddenNestedTypes() {
      var result =
          PublicApiRule.labelledOrListed(Set.of(InternalGreeterWithNestedTypes.Exposed.class))
              .evaluate(
                  new ClassFileImporter()
                      .importClasses(InternalGreeterWithNestedTypes.class.getDeclaredClasses()));

      assertThat(result.hasViolation()).isFalse();
    }

    @Test
    @DisplayName("pass for stratum-core and stratum-test, each with its own API list")
    void passForCoreAndTestLists() {
      var coreResult =
          evaluateOwnClasses(PublicApiRule.labelledOrListed(CORE_API_TYPES), CORE_PACKAGE);
      var testResult =
          evaluateOwnClasses(PublicApiRule.labelledOrListed(TEST_API_TYPES), TEST_PACKAGE);

      assertThat(coreResult.getFailureReport().getDetails()).isEmpty();
      assertThat(testResult.getFailureReport().getDetails()).isEmpty();
    }
  }
}
