package com.exepex.stratum.core.condition;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

@DisplayName("@ConditionalOnStratumModule turns a module on or off with stratum.<module>.enabled")
class ConditionalOnStratumModuleTest {

  /** The name of the main bean of the test module. */
  private static final String GREETING = "greeting";

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(GreetingModule.class);

  @Test
  @DisplayName("with the property unset, the module is off and the application still starts")
  void moduleIsOffWhenPropertyIsUnset() {
    contextRunner.run(
        context ->
            assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(GREETING)
                .doesNotHaveBean("greetingFormat"));
  }

  @Nested
  @DisplayName("with the property set")
  class WithProperty {

    @Test
    @DisplayName("true switches the module on, including every bean inside it")
    void trueSwitchesModuleOn() {
      contextRunner
          .withPropertyValues("stratum.greeting.enabled=true")
          .run(context -> assertThat(context).hasBean(GREETING).hasBean("greetingFormat"));
    }

    @Test
    @DisplayName("an environment variable switches the module on, as on Kubernetes")
    void environmentVariableSwitchesModuleOn() {
      contextRunner
          .withInitializer(
              context ->
                  context
                      .getEnvironment()
                      .getPropertySources()
                      .addFirst(
                          new SystemEnvironmentPropertySource(
                              StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                              Map.of("STRATUM_GREETING_ENABLED", "true"))))
          .run(context -> assertThat(context).hasBean(GREETING));
    }

    @Test
    @DisplayName("false switches the module off, including every bean inside it")
    void falseSwitchesModuleOff() {
      contextRunner
          .withPropertyValues("stratum.greeting.enabled=false")
          .run(
              context ->
                  assertThat(context)
                      .hasNotFailed()
                      .doesNotHaveBean(GREETING)
                      .doesNotHaveBean("greetingFormat"));
    }

    @Test
    @DisplayName("another module's switch does not affect this module")
    void otherModuleSwitchHasNoEffect() {
      contextRunner
          .withPropertyValues("stratum.greeting.enabled=true", "stratum.farewell.enabled=false")
          .run(context -> assertThat(context).hasBean(GREETING));
    }

    @Test
    @DisplayName("a value that is not a boolean stops startup")
    void nonBooleanValueStopsStartup() {
      contextRunner
          .withPropertyValues("stratum.greeting.enabled=maybe")
          .run(
              context ->
                  assertThat(context)
                      .getFailure()
                      .hasStackTraceContaining(BindException.class.getName())
                      .hasStackTraceContaining("'stratum.greeting.enabled'"));
    }
  }

  @Nested
  @DisplayName("on a single bean method")
  class OnBeanMethod {

    @Test
    @DisplayName("the switch decides that bean only")
    void switchDecidesOneBean() {
      new ApplicationContextRunner()
          .withUserConfiguration(FarewellBean.class)
          .withPropertyValues("stratum.farewell.enabled=false")
          .run(context -> assertThat(context).hasBean("alwaysThere").doesNotHaveBean("farewell"));
    }
  }

  @Nested
  @DisplayName("with a module name that is not in kebab-case")
  class WithBadModuleName {

    @ParameterizedTest(name = "{1}")
    @MethodSource("badlyNamedModules")
    @DisplayName("startup stops and names the bad module name")
    void badModuleNameStopsStartup(Class<?> module, String name) {
      new ApplicationContextRunner()
          .withUserConfiguration(module)
          .run(
              context ->
                  assertThat(context)
                      .getFailure()
                      .rootCause()
                      .isInstanceOf(IllegalStateException.class)
                      .hasMessageContaining("\"" + name + "\""));
    }

    /**
     * Returns each badly named module with its name.
     *
     * @return the module classes and their names
     */
    static Stream<Arguments> badlyNamedModules() {
      return Stream.of(
          Arguments.of(BadlyNamedModule.class, "Http_Client"),
          Arguments.of(DottedModule.class, "http.client"),
          Arguments.of(IndexedModule.class, "web[0]"),
          Arguments.of(UnnamedModule.class, ""),
          Arguments.of(BracketedModule.class, "[web]"));
    }
  }

  /** A module switched by {@code stratum.greeting.enabled}, with one bean inside it. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule("greeting")
  static class GreetingModule {

    /**
     * The module's main bean.
     *
     * @return a greeting
     */
    @Bean
    String greeting() {
      return "Hello";
    }

    /**
     * A second bean, to show the switch covers the whole module.
     *
     * @return a format pattern
     */
    @Bean
    String greetingFormat() {
      return "%s!";
    }
  }

  /** A configuration with one switched bean and one bean that is always created. */
  @Configuration(proxyBeanMethods = false)
  static class FarewellBean {

    /**
     * A bean with no switch.
     *
     * @return a constant
     */
    @Bean
    String alwaysThere() {
      return "here";
    }

    /**
     * A bean switched by {@code stratum.farewell.enabled}.
     *
     * @return a farewell
     */
    @Bean
    @ConditionalOnStratumModule("farewell")
    String farewell() {
      return "Bye";
    }
  }

  /** A module whose name breaks the kebab-case rule. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule("Http_Client")
  static class BadlyNamedModule {}

  /** A module whose name would make its switch a nested property. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule("http.client")
  static class DottedModule {}

  /** A module whose name would make its switch a list element. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule("web[0]")
  static class IndexedModule {}

  /** A module whose whole name is an index. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule("[web]")
  static class BracketedModule {}

  /** A module with no name. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule("")
  static class UnnamedModule {}
}
