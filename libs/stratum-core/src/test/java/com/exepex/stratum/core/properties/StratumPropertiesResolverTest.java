package com.exepex.stratum.core.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.core.properties.fixture.GreetingAutoConfiguration.GreetingProperties;
import com.exepex.stratum.core.properties.testsupport.GreetingTestData;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.stream.Stream;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("StratumPropertiesResolver works out the settings a module uses")
class StratumPropertiesResolverTest {

  /** How every message about a broken customizer starts, before the customizer's name. */
  private static final String BROKEN_CUSTOMIZER =
      "SX-CORE-1002: A StratumPropertiesCustomizer is broken: ";

  /** Hibernate Validator, as Spring Boot uses it; JUnit closes it after the last test. */
  @AutoClose
  private static final ValidatorFactory VALIDATOR_FACTORY =
      Validation.buildDefaultValidatorFactory();

  /** Resolves the test module's settings. */
  private final StratumPropertiesResolver resolver =
      new StratumPropertiesResolver(VALIDATOR_FACTORY.getValidator());

  @Nested
  @DisplayName("when the service has no Java configuration")
  class WithoutCustomizers {

    @Test
    @DisplayName("it returns the settings bound from properties unchanged")
    void returnsBoundPropertiesWhenNoCustomizerExists() {
      var bound = GreetingTestData.defaults().build();

      assertThat(resolver.resolve(bound, Stream.empty())).isSameAs(bound);
    }
  }

  @Nested
  @DisplayName("when the service has Java configuration")
  class WithCustomizers {

    @Test
    @DisplayName("the value set in Java wins over the value bound from properties")
    void javaValueWinsOverBoundValue() {
      var resolved =
          resolver.resolve(
              GreetingTestData.defaults().build(), Stream.of(GreetingTestData.javaTimeout()));

      assertThat(resolved.timeout()).isEqualTo(GreetingTestData.JAVA_TIMEOUT);
      assertThat(resolved.message()).isEqualTo("Hello");
    }

    @Test
    @DisplayName("each customizer receives the result of the one before it")
    void appliesCustomizersInTheGivenOrder() {
      var resolved =
          resolver.resolve(
              GreetingTestData.defaults().build(),
              Stream.of(
                  GreetingTestData.javaMessage("first"),
                  GreetingTestData.javaTimeout(),
                  GreetingTestData.javaMessage("last")));

      assertThat(resolved)
          .isEqualTo(
              GreetingProperties.builder()
                  .message("last")
                  .timeout(GreetingTestData.JAVA_TIMEOUT)
                  .build());
    }

    @Test
    @DisplayName("a customizer that returns its input leaves the settings unchanged")
    void keepsPropertiesWhenCustomizerReturnsItsInput() {
      var bound = GreetingTestData.defaults().build();
      StratumPropertiesCustomizer<GreetingProperties> unchanged = properties -> properties;

      assertThat(resolver.resolve(bound, Stream.of(unchanged))).isSameAs(bound);
    }
  }

  @Nested
  @DisplayName("when the Java configuration is wrong")
  class WithFaultyCustomizers {

    @Test
    @DisplayName("a customizer that returns null stops startup and is named in the message")
    void rejectsNullFromCustomizer() {
      StratumPropertiesCustomizer<GreetingProperties> broken = new ReturnsNull();

      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(
              () -> resolver.resolve(GreetingTestData.defaults().build(), Stream.of(broken)))
          .withMessageStartingWith(BROKEN_CUSTOMIZER + ReturnsNull.class.getName())
          .withMessageContaining("returned null");
    }

    @Test
    @DisplayName(
        "a customizer written for other settings stops startup and is named in the message")
    void rejectsCustomizerForOtherProperties() {
      StratumPropertiesCustomizer<Object> forStrings = properties -> ((String) properties).strip();

      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(
              () ->
                  resolver.resolve(
                      (Object) GreetingTestData.defaults().build(), Stream.of(forStrings)))
          .withMessageStartingWith(
              BROKEN_CUSTOMIZER + StratumPropertiesResolverTest.class.getName())
          .withMessageContaining("StratumPropertiesCustomizer<GreetingProperties>")
          .withCauseInstanceOf(ClassCastException.class);
    }

    @Test
    @DisplayName("a bad cast in a customizer's own code is not blamed only on its type")
    void pointsAtCastInCustomizerCode() {
      Object text = "x";
      StratumPropertiesCustomizer<GreetingProperties> buggy =
          properties -> properties.toBuilder().message(((Integer) text).toString()).build();

      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(() -> resolver.resolve(GreetingTestData.defaults().build(), Stream.of(buggy)))
          .withMessageStartingWith(BROKEN_CUSTOMIZER)
          .withMessageContaining("Otherwise see the cause")
          .withCauseInstanceOf(ClassCastException.class);
    }

    @Test
    @DisplayName("a customizer that returns settings of another type stops startup")
    void rejectsResultOfAnotherType() {
      StratumPropertiesCustomizer<Object> wrongResult = properties -> "not the settings";

      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(
              () ->
                  resolver.resolve(
                      (Object) GreetingTestData.defaults().build(), Stream.of(wrongResult)))
          .withMessageStartingWith(BROKEN_CUSTOMIZER)
          .withMessageContaining("returned null or settings of another type")
          .withMessageContaining("declare it as StratumPropertiesCustomizer<GreetingProperties>")
          .withNoCause();
    }

    @Test
    @DisplayName("a value set in Java that breaks a constraint stops startup")
    void rejectsInvalidValueSetInJava() {
      var blank = "   ";

      assertThatExceptionOfType(ConstraintViolationException.class)
          .isThrownBy(
              () ->
                  resolver.resolve(
                      GreetingTestData.defaults().build(),
                      Stream.of(GreetingTestData.javaMessage(blank))))
          .withMessage("SX-CORE-1003: Invalid settings: stratum.greeting.message breaks @NotBlank")
          .satisfies(failure -> assertThat(failure.getConstraintViolations()).hasSize(1));
    }

    @Test
    @DisplayName("every broken constraint is listed, in a stable order, without the values")
    void listsEveryViolationSortedWithoutValues() {
      StratumPropertiesCustomizer<GreetingProperties> noTimeout =
          properties -> properties.toBuilder().timeout(null).build();
      StratumPropertiesCustomizer<GreetingProperties> blank = GreetingTestData.javaMessage("");

      assertThatExceptionOfType(ConstraintViolationException.class)
          .isThrownBy(
              () ->
                  resolver.resolve(
                      GreetingTestData.defaults().build(), Stream.of(noTimeout, blank)))
          .withMessage(
              "SX-CORE-1003: Invalid settings: stratum.greeting.message breaks @NotBlank;"
                  + " stratum.greeting.timeout breaks @NotNull");
    }
  }

  /** A customizer that breaks its contract by returning {@code null}. */
  private static final class ReturnsNull
      implements StratumPropertiesCustomizer<GreetingProperties> {

    @Override
    public GreetingProperties customize(GreetingProperties properties) {
      return null;
    }
  }
}
