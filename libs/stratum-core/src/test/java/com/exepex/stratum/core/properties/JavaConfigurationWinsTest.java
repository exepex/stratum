package com.exepex.stratum.core.properties;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.core.properties.fixture.Greeter;
import com.exepex.stratum.core.properties.fixture.GreetingAutoConfiguration;
import com.exepex.stratum.core.properties.fixture.GreetingProperties;
import com.exepex.stratum.core.properties.testsupport.GreetingTestData;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@DisplayName("A module's settings come from properties, YAML and Java, and Java wins")
class JavaConfigurationWinsTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ValidationAutoConfiguration.class, GreetingAutoConfiguration.class));

  @Test
  @DisplayName("with nothing set, the module uses its defaults")
  void usesDefaultsWhenNothingIsSet() {
    contextRunner.run(
        context ->
            assertThat(context.getBean(Greeter.class).settings())
                .isEqualTo(GreetingTestData.defaults()));
  }

  @Nested
  @DisplayName("with values in a configuration file")
  class FromFiles {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"greeting/application.properties", "greeting/application.yml"})
    @DisplayName("the module uses the values from application.properties and application.yml")
    void bindsValuesFromFile(String file) {
      contextRunner
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withPropertyValues("spring.config.location=classpath:" + file)
          .run(
              context ->
                  assertThat(context.getBean(Greeter.class).settings())
                      .isEqualTo(
                          GreetingProperties.builder()
                              .message("Hi from a file")
                              .timeout(GreetingTestData.FILE_TIMEOUT)
                              .build()));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"greeting/application.properties", "greeting/application.yml"})
    @DisplayName("a value set in Java wins over the same value in the file")
    void javaValueWinsOverFileValue(String file) {
      contextRunner
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withPropertyValues("spring.config.location=classpath:" + file)
          .withUserConfiguration(JavaTimeout.class)
          .run(
              context ->
                  assertThat(context.getBean(Greeter.class).settings())
                      .isEqualTo(
                          GreetingProperties.builder()
                              .message("Hi from a file")
                              .timeout(GreetingTestData.JAVA_TIMEOUT)
                              .build()));
    }

    @Test
    @DisplayName("a bad value in a file stops startup")
    void badFileValueStopsStartup() {
      contextRunner
          .withPropertyValues("stratum.greeting.message=")
          .run(
              context ->
                  assertThat(context)
                      .getFailure()
                      .rootCause()
                      .isInstanceOf(ConstraintViolationException.class)
                      .hasMessage(
                          "SX-CORE-1003: Invalid settings:"
                              + " stratum.greeting.message breaks @NotBlank"));
    }

    @Test
    @DisplayName("a bad value in a file that Java configuration corrects does not stop startup")
    void javaCorrectsBadFileValue() {
      contextRunner
          .withPropertyValues("stratum.greeting.message=")
          .withUserConfiguration(JavaMessage.class)
          .run(
              context ->
                  assertThat(context.getBean(Greeter.class).settings().message())
                      .isEqualTo("Hi from Java"));
    }
  }

  @Nested
  @DisplayName("with values in Java")
  class FromJava {

    @Test
    @DisplayName("customizers run in @Order order, so the last one wins")
    void customizersRunInOrder() {
      contextRunner
          .withUserConfiguration(OrderedCustomizers.class)
          .run(
              context ->
                  assertThat(context.getBean(Greeter.class).settings().message())
                      .isEqualTo("second"));
    }

    @Test
    @DisplayName("a bad value set in Java stops startup")
    void badJavaValueStopsStartup() {
      contextRunner
          .withUserConfiguration(BlankMessage.class)
          .run(
              context ->
                  assertThat(context)
                      .getFailure()
                      .rootCause()
                      .isInstanceOf(ConstraintViolationException.class));
    }

    @Test
    @DisplayName("a customizer for another module's settings is ignored")
    void ignoresCustomizerForOtherSettings() {
      contextRunner
          .withUserConfiguration(OtherModule.class)
          .run(
              context ->
                  assertThat(context.getBean(Greeter.class).settings())
                      .isEqualTo(GreetingTestData.defaults()));
    }

    @Test
    @DisplayName("a customizer registered without its settings type stops startup, naming it")
    void untypedCustomizerStopsStartup() {
      contextRunner
          .withBean("untyped", StratumPropertiesCustomizer.class, GreetingTestData::otherModule)
          .run(
              context ->
                  assertThat(context)
                      .getFailure()
                      .hasStackTraceContaining(
                          StratumException.class.getName()
                              + ": SX-CORE-1002: A StratumPropertiesCustomizer is broken: "
                              + GreetingTestData.class.getName())
                      .hasStackTraceContaining(
                          "declare it as StratumPropertiesCustomizer<GreetingProperties>"));
    }
  }

  /** Sets the timeout in Java. */
  @Configuration(proxyBeanMethods = false)
  static class JavaTimeout {

    /**
     * Sets the timeout to {@link GreetingTestData#JAVA_TIMEOUT}.
     *
     * @return the customizer
     */
    @Bean
    StratumPropertiesCustomizer<GreetingProperties> javaTimeout() {
      return GreetingTestData.javaTimeout();
    }
  }

  /** Sets the message in Java. */
  @Configuration(proxyBeanMethods = false)
  static class JavaMessage {

    /**
     * Sets the message to {@code Hi from Java}.
     *
     * @return the customizer
     */
    @Bean
    StratumPropertiesCustomizer<GreetingProperties> javaMessage() {
      return GreetingTestData.javaMessage("Hi from Java");
    }
  }

  /** Sets a blank message in Java, which breaks the module's constraints. */
  @Configuration(proxyBeanMethods = false)
  static class BlankMessage {

    /**
     * Sets the message to an empty string.
     *
     * @return the customizer
     */
    @Bean
    StratumPropertiesCustomizer<GreetingProperties> blankMessage() {
      return GreetingTestData.javaMessage("");
    }
  }

  /** Two customizers declared out of order, to show that {@code @Order} decides. */
  @Configuration(proxyBeanMethods = false)
  static class OrderedCustomizers {

    /**
     * Runs second, because of its higher order value.
     *
     * @return a customizer that sets the message to {@code second}
     */
    @Bean
    @Order(2)
    StratumPropertiesCustomizer<GreetingProperties> second() {
      return GreetingTestData.javaMessage("second");
    }

    /**
     * Runs first, because of its lower order value.
     *
     * @return a customizer that sets the message to {@code first}
     */
    @Bean
    @Order(1)
    StratumPropertiesCustomizer<GreetingProperties> first() {
      return GreetingTestData.javaMessage("first");
    }
  }

  /** A customizer for another module's settings, which the test module must ignore. */
  @Configuration(proxyBeanMethods = false)
  static class OtherModule {

    /**
     * Changes settings of another type, here a plain {@code String}.
     *
     * @return the customizer
     */
    @Bean
    StratumPropertiesCustomizer<String> otherModule() {
      return GreetingTestData.otherModule();
    }
  }
}
