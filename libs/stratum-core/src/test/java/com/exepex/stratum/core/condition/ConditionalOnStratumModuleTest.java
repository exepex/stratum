package com.exepex.stratum.core.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.StratumException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

@DisplayName("@ConditionalOnStratumModule turns a module on or off with stratum.<module>.enabled")
class ConditionalOnStratumModuleTest {

  /** The name of the main bean of the test module. */
  private static final String GREETING = "greeting";

  /** The test module's switch. */
  private static final String GREETING_SWITCH = "stratum.greeting.enabled";

  /** A file that switches the test module on, loaded through {@code @PropertySource}. */
  private static final String LATE_SWITCH_FILE = "late-switch/greeting.properties";

  /** The name of the test module's second bean. */
  private static final String GREETING_FORMAT = "greetingFormat";

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(GreetingModule.class);

  /** Returns the condition report's message for the test module, as {@code --debug} shows it. */
  private String reportMessage(AssertableApplicationContext context) {
    return ConditionEvaluationReport.get(context.getBeanFactory())
        .getConditionAndOutcomesBySource()
        .get(GreetingModule.class.getName())
        .iterator()
        .next()
        .getOutcome()
        .getMessage();
  }

  @Test
  @DisplayName("the condition report says whether the property is true")
  void reportSaysWhetherPropertyIsTrue() {
    contextRunner
        .withPropertyValues("stratum.greeting.enabled=true")
        .run(
            context ->
                assertThat(reportMessage(context)).endsWith("stratum.greeting.enabled is true"));
    contextRunner.run(
        context ->
            assertThat(reportMessage(context))
                .endsWith("stratum.greeting.enabled is not true, so it is off"));
  }

  @Test
  @DisplayName("with the property unset, the module is off and the application still starts")
  void moduleIsOffWhenPropertyIsUnset() {
    contextRunner.run(
        context ->
            assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(GREETING)
                .doesNotHaveBean(GREETING_FORMAT));
  }

  @Nested
  @DisplayName("with the property set")
  class WithProperty {

    @Test
    @DisplayName("true switches the module on, including every bean inside it")
    void trueSwitchesModuleOn() {
      contextRunner
          .withPropertyValues("stratum.greeting.enabled=true")
          .run(context -> assertThat(context).hasBean(GREETING).hasBean(GREETING_FORMAT));
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
                      .doesNotHaveBean(GREETING_FORMAT));
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

    /** Each badly named module with its name. */
    private static final List<Arguments> BADLY_NAMED_MODULES =
        List.of(
            Arguments.of(BadlyNamedModule.class, "Http_Client"),
            Arguments.of(DottedModule.class, "http.client"),
            Arguments.of(IndexedModule.class, "web[0]"),
            Arguments.of(UnnamedModule.class, ""),
            Arguments.of(BracketedModule.class, "[web]"),
            Arguments.of(LeadingHyphenModule.class, "-http"),
            Arguments.of(TrailingHyphenModule.class, "http-"),
            Arguments.of(DoubleHyphenModule.class, "http--client"));

    @ParameterizedTest(name = "{1}")
    @FieldSource("BADLY_NAMED_MODULES")
    @DisplayName("startup stops and names the bad module name and the class that has it")
    void badModuleNameStopsStartup(Class<?> module, String name) {
      new ApplicationContextRunner()
          .withUserConfiguration(module)
          .run(
              context ->
                  assertThat(context)
                      .getFailure()
                      .rootCause()
                      .isInstanceOf(StratumException.class)
                      .hasMessageStartingWith("SX-CORE-1001: ")
                      .hasMessageContaining("\"" + name + "\" on " + module.getName()));
    }
  }

  @Nested
  @DisplayName("when a Spring Boot application starts")
  class InStartedApplication {

    /** Returns an application without a web server, from the given configuration. */
    private SpringApplication application(Class<?> configuration) {
      var application = new SpringApplication(configuration);
      application.setWebApplicationType(WebApplicationType.NONE);
      return application;
    }

    @Test
    @DisplayName("a switch the modules' startup steps could read turns the module on")
    void earlySwitchTurnsModuleOn() {
      var application = application(GreetingModule.class);
      application.setDefaultProperties(Map.of(GREETING_SWITCH, true));

      try (var context = application.run()) {
        assertThat(context.containsBean(GREETING)).isTrue();
      }
    }

    @Test
    @DisplayName(
        "a switch also set later, as remote configuration does with Spring Cloud's bootstrap,"
            + " still starts the module, because it was already on")
    void switchSetEarlyAndAgainLaterTurnsModuleOn() {
      var application = application(GreetingModule.class);
      application.setDefaultProperties(Map.of(GREETING_SWITCH, true));
      application.addInitializers(
          context ->
              context
                  .getEnvironment()
                  .getPropertySources()
                  .addFirst(new MapPropertySource("remote-config", Map.of(GREETING_SWITCH, true))));

      try (var context = application.run()) {
        assertThat(context.containsBean(GREETING)).isTrue();
      }
    }

    @Test
    @DisplayName(
        "a switch only in a @PropertySource file stops startup instead of a half-on module")
    void lateSwitchStopsStartup() {
      assertThatExceptionOfType(Exception.class)
          .isThrownBy(() -> application(LateSwitchedGreeting.class).run())
          .havingRootCause()
          .isInstanceOf(StratumException.class)
          .withMessageStartingWith("SX-CORE-1006: ")
          .withMessageContaining(GREETING_SWITCH + " in class path resource [" + LATE_SWITCH_FILE);
    }
  }

  /** A service that switches the greeting module on only in a {@code @PropertySource} file. */
  @Configuration(proxyBeanMethods = false)
  @PropertySource("classpath:" + LATE_SWITCH_FILE)
  @Import(GreetingModule.class)
  static class LateSwitchedGreeting {}

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

  /** A module whose name starts with a hyphen. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule("-http")
  static class LeadingHyphenModule {}

  /** A module whose name ends in a hyphen. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule("http-")
  static class TrailingHyphenModule {}

  /** A module whose name has two hyphens in a row. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule("http--client")
  static class DoubleHyphenModule {}

  /** A module with no name. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule("")
  static class UnnamedModule {}
}
