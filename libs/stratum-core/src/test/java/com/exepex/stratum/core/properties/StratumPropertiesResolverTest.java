package com.exepex.stratum.core.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.exepex.stratum.core.properties.fixture.GreetingProperties;
import com.exepex.stratum.core.properties.testsupport.GreetingTestData;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("StratumPropertiesResolver works out the settings a module uses")
class StratumPropertiesResolverTest {

  private static ValidatorFactory validatorFactory;

  private static StratumPropertiesResolver resolver;

  /** Builds one resolver for every test, backed by Hibernate Validator. */
  @BeforeAll
  static void createResolver() {
    validatorFactory = Validation.buildDefaultValidatorFactory();
    resolver = new StratumPropertiesResolver(validatorFactory.getValidator());
  }

  /** Releases the validator factory's resources. */
  @AfterAll
  static void closeValidatorFactory() {
    validatorFactory.close();
  }

  @Nested
  @DisplayName("when the service has no Java configuration")
  class WithoutCustomizers {

    @Test
    @DisplayName("it returns the settings bound from properties unchanged")
    void returnsBoundSettingsWhenNoCustomizerExists() {
      var bound = GreetingTestData.defaults();

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
          resolver.resolve(GreetingTestData.defaults(), Stream.of(GreetingTestData.javaTimeout()));

      assertThat(resolved.timeout()).isEqualTo(GreetingTestData.JAVA_TIMEOUT);
      assertThat(resolved.message()).isEqualTo("Hello");
    }

    @Test
    @DisplayName("each customizer receives the result of the one before it")
    void appliesCustomizersInTheGivenOrder() {
      var resolved =
          resolver.resolve(
              GreetingTestData.defaults(),
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
    void keepsSettingsWhenCustomizerReturnsItsInput() {
      var bound = GreetingTestData.defaults();
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

      assertThatIllegalStateException()
          .isThrownBy(() -> resolver.resolve(GreetingTestData.defaults(), Stream.of(broken)))
          .withMessageContaining(ReturnsNull.class.getName())
          .withMessageContaining("returned null");
    }

    @Test
    @DisplayName(
        "a customizer written for other settings stops startup and is named in the message")
    void rejectsCustomizerForOtherSettings() {
      StratumPropertiesCustomizer<Object> forStrings = settings -> ((String) settings).strip();

      assertThatIllegalStateException()
          .isThrownBy(
              () -> resolver.resolve((Object) GreetingTestData.defaults(), Stream.of(forStrings)))
          .withMessageContaining(StratumPropertiesResolverTest.class.getName())
          .withMessageContaining("StratumPropertiesCustomizer<GreetingProperties>")
          .withCauseInstanceOf(ClassCastException.class);
    }

    @Test
    @DisplayName("a bad cast in a customizer's own code is not blamed only on its type")
    void pointsAtCastInCustomizerCode() {
      Object text = "x";
      StratumPropertiesCustomizer<GreetingProperties> buggy =
          properties -> properties.toBuilder().message(((Integer) text).toString()).build();

      assertThatIllegalStateException()
          .isThrownBy(() -> resolver.resolve(GreetingTestData.defaults(), Stream.of(buggy)))
          .withMessageContaining("the cast that failed is in the customizer's own code")
          .withCauseInstanceOf(ClassCastException.class);
    }

    @Test
    @DisplayName("a customizer that returns settings of another type stops startup")
    void rejectsResultOfAnotherType() {
      StratumPropertiesCustomizer<Object> wrongResult = properties -> "not the settings";

      assertThatIllegalStateException()
          .isThrownBy(
              () -> resolver.resolve((Object) GreetingTestData.defaults(), Stream.of(wrongResult)))
          .withMessageContaining("cannot change GreetingProperties")
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
                      GreetingTestData.defaults(), Stream.of(GreetingTestData.javaMessage(blank))))
          .withMessage("Invalid GreetingProperties: stratum.greeting.message breaks @NotBlank")
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
              () -> resolver.resolve(GreetingTestData.defaults(), Stream.of(noTimeout, blank)))
          .withMessage(
              "Invalid GreetingProperties: stratum.greeting.message breaks @NotBlank;"
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
