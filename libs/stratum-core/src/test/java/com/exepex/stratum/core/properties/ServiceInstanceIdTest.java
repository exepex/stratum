package com.exepex.stratum.core.properties;

import static com.exepex.stratum.core.properties.testsupport.ServiceInstanceIdTestData.GENERATED_ID;
import static com.exepex.stratum.core.properties.testsupport.ServiceInstanceIdTestData.RESOURCE_ATTRIBUTES_VARIABLE;
import static com.exepex.stratum.core.properties.testsupport.ServiceInstanceIdTestData.SPRING_BOOT_DOTTED_KEY;
import static com.exepex.stratum.core.properties.testsupport.ServiceInstanceIdTestData.SPRING_BOOT_KEY;
import static com.exepex.stratum.core.properties.testsupport.ServiceInstanceIdTestData.TEAM_ID;
import static com.exepex.stratum.core.properties.testsupport.ServiceInstanceIdTestData.withResourceAttributesVariable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("ServiceInstanceId names one running instance of the service")
class ServiceInstanceIdTest {

  /** Returns the ID generated for this JVM, as an environment with nothing set gets it. */
  private String generatedInThisJvm() {
    return ServiceInstanceId.of(new MockEnvironment()).value();
  }

  @Nested
  @DisplayName("with no ID set by the service")
  class Generated {

    /** A fixed seed, so the drawn IDs are the same on every run. */
    private static final long SEED = 42L;

    /** Enough IDs (1,000 characters) for every one of the 36 characters to appear. */
    private static final int GENERATED_IDS = 100;

    /** The characters an ID may hold, in order. */
    private static final String DIGITS_AND_LOWERCASE_LETTERS =
        "0123456789abcdefghijklmnopqrstuvwxyz";

    @Test
    @DisplayName("it is ten random digits and lowercase letters")
    void isTenDigitsAndLowercaseLetters() {
      assertThat(generatedInThisJvm()).matches(GENERATED_ID);
    }

    @Test
    @DisplayName(
        "its characters come from all 36 digits and lowercase letters, as the clash odds need")
    void usesAllDigitsAndLowercaseLetters() {
      var seeded = new SplittableRandom(SEED);
      var usedCharacters =
          IntStream.range(0, GENERATED_IDS)
              .mapToObj(ignored -> ServiceInstanceId.generate(seeded))
              .flatMapToInt(String::chars)
              .mapToObj(Character::toString)
              .collect(Collectors.toCollection(TreeSet::new));

      assertThat(String.join("", usedCharacters)).isEqualTo(DIGITS_AND_LOWERCASE_LETTERS);
    }

    @Test
    @DisplayName("it is made once per JVM, so every module and application context shares it")
    void isTheSameForEveryEnvironment() {
      var first = ServiceInstanceId.of(new MockEnvironment());
      var second = ServiceInstanceId.of(new MockEnvironment().withProperty("other.key", "value"));

      assertThat(second).isEqualTo(first);
    }
  }

  @Nested
  @DisplayName("with Spring Boot's key set")
  class FromSpringBootKey {

    /** Blank values a service can set, such as an unset placeholder {@code ${POD_ID:}}. */
    private static final List<String> BLANK_VALUES = List.of("", " ");

    @ParameterizedTest(name = "\"{0}\"")
    @FieldSource("BLANK_VALUES")
    @DisplayName(
        "a blank value is used as it is, because Spring Boot sends it on spans and metrics too")
    void blankValueIsUsed(String blank) {
      var environment = new MockEnvironment().withProperty(SPRING_BOOT_KEY, blank);

      assertThat(ServiceInstanceId.of(environment).value()).isEqualTo(blank);
    }

    @Test
    @DisplayName("the bracket form, as in application.yml, wins")
    void bracketFormWins() {
      var environment = new MockEnvironment().withProperty(SPRING_BOOT_KEY, TEAM_ID);

      assertThat(ServiceInstanceId.of(environment).value()).isEqualTo(TEAM_ID);
    }

    @Test
    @DisplayName("the dotted form, as in application.properties, wins")
    void dottedFormWins() {
      var environment = new MockEnvironment().withProperty(SPRING_BOOT_DOTTED_KEY, TEAM_ID);

      assertThat(ServiceInstanceId.of(environment).value()).isEqualTo(TEAM_ID);
    }

    @Test
    @DisplayName("a value set in Java, through SpringApplication's default properties, wins")
    void javaDefaultPropertiesWin() {
      var environment = new MockEnvironment();
      DefaultPropertiesPropertySource.addOrMerge(
          Map.of(SPRING_BOOT_KEY, TEAM_ID), environment.getPropertySources());

      assertThat(ServiceInstanceId.of(environment).value()).isEqualTo(TEAM_ID);
    }

    @Test
    @DisplayName("it wins over OTEL_RESOURCE_ATTRIBUTES, as Spring Boot's own resource does")
    void winsOverEnvironmentVariable() {
      var environment =
          withResourceAttributesVariable("service.instance.id=pod-1")
              .withProperty(SPRING_BOOT_KEY, TEAM_ID);

      assertThat(ServiceInstanceId.of(environment).value()).isEqualTo(TEAM_ID);
    }
  }

  @Nested
  @DisplayName("with OTEL_RESOURCE_ATTRIBUTES set")
  class FromResourceAttributesVariable {

    /** Variable values that set the ID, each with the ID Spring Boot reads from it. */
    private static final List<Arguments> SETTING_VALUES =
        List.of(
            argumentSet("only the ID", "service.instance.id=pod-1", "pod-1"),
            argumentSet("spaces around", " service.instance.id = pod-1 ", "pod-1"),
            argumentSet("among others", "k8s.pod.name=x,service.instance.id=pod-1,a=b", "pod-1"),
            argumentSet("twice: the last wins", "service.instance.id=a,service.instance.id=b", "b"),
            argumentSet("percent-encoded", "service.instance.id=orders%2Cblue", "orders,blue"));

    /** Variable values that set no usable ID, so the generated one is used. */
    private static final List<String> NOT_SETTING_VALUES =
        List.of(
            "",
            "k8s.pod.name=pod-1",
            "service.instance.id",
            "service.instance.id=",
            "service.instance.id=pod-1,service.instance.id= ",
            "=pod-1",
            "service.instance.id=%zz");

    @ParameterizedTest
    @FieldSource("SETTING_VALUES")
    @DisplayName("the ID in it is used, read as Spring Boot reads it")
    void idInVariableIsUsed(String variable, String expectedId) {
      assertThat(ServiceInstanceId.of(withResourceAttributesVariable(variable)).value())
          .isEqualTo(expectedId);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @FieldSource("NOT_SETTING_VALUES")
    @DisplayName(
        "a blank, missing or badly encoded ID is skipped, and startup goes on with the generated"
            + " one")
    void unusableIdIsSkipped(String variable) {
      assertThat(ServiceInstanceId.of(withResourceAttributesVariable(variable)).value())
          .isEqualTo(generatedInThisJvm());
    }

    @Test
    @DisplayName("a property of that name in a file is ignored, as Spring Boot ignores it")
    void sameNameInFileIsIgnored() {
      var environment =
          new MockEnvironment()
              .withProperty(RESOURCE_ATTRIBUTES_VARIABLE, "service.instance.id=pod-1");

      assertThat(ServiceInstanceId.of(environment).value()).isEqualTo(generatedInThisJvm());
    }
  }
}
