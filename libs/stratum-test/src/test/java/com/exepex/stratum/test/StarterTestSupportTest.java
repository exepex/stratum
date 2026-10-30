package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.CoreErrorCode;
import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.Severity;
import com.exepex.stratum.core.properties.StratumPropertiesCustomizer;
import com.exepex.stratum.test.fixture.UnswitchedGreeter;
import com.exepex.stratum.test.fixture.autoconfigure.GreetingAutoConfiguration;
import com.exepex.stratum.test.fixture.autoconfigure.GreetingFormatter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("StarterTestSupport gives every starter's tests the same checks and set-up")
class StarterTestSupportTest {

  /** The made-up starter's top package, which the architecture rules are checked against. */
  private static final String FIXTURE_STARTER_PACKAGE = "com.exepex.stratum.test.fixture";

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
  @DisplayName("checkErrorCodes")
  class CheckErrorCodes {

    @Test
    @DisplayName("passes when the codes have the format and the page lists exactly them")
    void passesForDocumentedCodes() throws IOException {
      StarterTestSupport.checkErrorCodes("CORE", CoreErrorCode.values());
    }

    @Test
    @DisplayName("fails when a code is not on the error codes page")
    void failsForUndocumentedCode() {
      assertThatExceptionOfType(AssertionError.class)
          .isThrownBy(() -> StarterTestSupport.checkErrorCodes("CORE", UndocumentedCode.values()));
    }

    @Test
    @DisplayName("fails when a code does not have the module's format")
    void failsForBadlyFormattedCode() {
      assertThatExceptionOfType(AssertionError.class)
          .isThrownBy(() -> StarterTestSupport.checkErrorCodes("LOGGING", CoreErrorCode.values()));
    }
  }

  @Nested
  @DisplayName("the starter architecture rules")
  class StarterArchitecture {

    @Test
    @DisplayName("pass for an autoconfigure package that only declares beans")
    void passForConfigurationClass() {
      var result =
          StarterTestSupport.starterArchitecture(FIXTURE_STARTER_PACKAGE)
              .evaluate(new ClassFileImporter().importClasses(GreetingAutoConfiguration.class));

      assertThat(result.hasViolation()).isFalse();
    }

    @Test
    @DisplayName("fail for logic in the autoconfigure package")
    void failForLogicInAutoconfigurePackage() {
      var result =
          StarterTestSupport.starterArchitecture(FIXTURE_STARTER_PACKAGE)
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

  /** A code in the right format that the error codes page does not list. */
  @Getter
  @RequiredArgsConstructor
  enum UndocumentedCode implements ErrorCode {
    NOT_ON_THE_PAGE("SX-CORE-9999", Severity.ERROR, "Not on the error codes page");

    private final String code;

    private final Severity severity;

    private final String description;
  }
}
