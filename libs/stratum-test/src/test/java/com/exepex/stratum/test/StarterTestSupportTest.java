package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.properties.StratumPropertiesCustomizer;
import com.exepex.stratum.test.fixture.UnswitchedGreeter;
import com.exepex.stratum.test.fixture.autoconfigure.GreetingAutoConfiguration;
import com.exepex.stratum.test.fixture.autoconfigure.GreetingFormatter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("StarterTestSupport gives every starter's tests the same checks and set-up")
class StarterTestSupportTest {

  /** The made-up starter's top package, which the architecture rules are checked against. */
  private static final String FIXTURE_STARTER_PACKAGE = "com.exepex.stratum.test.fixture";

  /** The made-up starter's public classes that services may use, as its API list. */
  private static final Set<Class<?>> FIXTURE_API_TYPES =
      Set.of(GreetingAutoConfiguration.class, GreetingFormatter.class);

  /** How the API list rule reports a class, after the class name. */
  private static final String NOT_LISTED = "> is not on the module's API list";

  @Nested
  @DisplayName("withCustomizers")
  class WithCustomizers {

    @Test
    @DisplayName("lists the customizers under their interface while the work runs, then cleans up")
    void listsCustomizersOnlyWhileWorkRuns(@TempDir Path folder) throws IOException {
      var thread = Thread.currentThread();
      var originalClassLoader = thread.getContextClassLoader();

      var listedCustomizers =
          StarterTestSupport.withCustomizers(
              folder,
              GreetingPropertiesCustomizer.class,
              List.of(ShortGreeting.class),
              () -> thread.getContextClassLoader().getResource("META-INF/spring.factories"));

      assertThat(listedCustomizers).isNotNull();
      assertThat(Files.readString(folder.resolve("META-INF/spring.factories")))
          .isEqualTo(
              GreetingPropertiesCustomizer.class.getName() + "=" + ShortGreeting.class.getName());
      assertThat(thread.getContextClassLoader()).isSameAs(originalClassLoader);
    }
  }

  @Nested
  @DisplayName("the starter architecture rules")
  class StarterArchitecture {

    @Test
    @DisplayName("pass for an autoconfigure package that only declares beans")
    void passForConfigurationClass() {
      var result =
          StarterTestSupport.starterArchitecture(FIXTURE_STARTER_PACKAGE, FIXTURE_API_TYPES)
              .evaluate(new ClassFileImporter().importClasses(GreetingAutoConfiguration.class));

      assertThat(result.hasViolation()).isFalse();
    }

    @Test
    @DisplayName("fail for logic in the autoconfigure package")
    void failForLogicInAutoconfigurePackage() {
      var result =
          StarterTestSupport.starterArchitecture(FIXTURE_STARTER_PACKAGE, FIXTURE_API_TYPES)
              .evaluate(new ClassFileImporter().importClasses(GreetingFormatter.class));

      assertThat(result.getFailureReport().getDetails())
          .anyMatch(detail -> detail.contains(GreetingFormatter.class.getName()));
    }

    @Test
    @DisplayName("fail for a class a component scan picks up without the module switch")
    void failForScannableClassWithoutModuleSwitch() {
      var result =
          StarterTestSupport.safeToScan()
              .evaluate(new ClassFileImporter().importClasses(UnswitchedGreeter.class));

      assertThat(result.getFailureReport().getDetails())
          .anyMatch(detail -> detail.contains(UnswitchedGreeter.class.getName()));
    }

    @Test
    @DisplayName("fail for a public class with no label that is not on the starter's API list")
    void failForUnlistedClass() {
      var result =
          StarterTestSupport.starterArchitecture(FIXTURE_STARTER_PACKAGE, Set.of())
              .evaluate(new ClassFileImporter().importClasses(GreetingAutoConfiguration.class));

      assertThat(result.getFailureReport().getDetails())
          .anyMatch(
              detail -> detail.contains(GreetingAutoConfiguration.class.getName() + NOT_LISTED));
    }
  }

  @Nested
  @DisplayName("the library architecture rules")
  class LibraryArchitecture {

    // The check imports a library's main classes only, so it runs on stratum-test's own.
    @Test
    @DisplayName("fail for a public class with no label that is not on the library's API list")
    void failForUnlistedClass() {
      assertThatExceptionOfType(AssertionError.class)
          .isThrownBy(
              () -> StarterTestSupport.checkLibraryArchitecture(PublicApiRule.class, Set.of()))
          .withMessageContaining(PostgresContainerConfiguration.class.getName() + NOT_LISTED);
    }
  }

  /** A module's customizer interface, as a starter declares one. */
  @FunctionalInterface
  interface GreetingPropertiesCustomizer extends StratumPropertiesCustomizer<String> {}

  /** A service's customizer, which the test lists. */
  static class ShortGreeting implements GreetingPropertiesCustomizer {

    @Override
    public String customize(String greeting) {
      return "Hi";
    }
  }
}
