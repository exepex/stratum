package com.exepex.stratum.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.error.StratumException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

@DisplayName("A module switch read when a Spring Boot application starts")
class ModuleSwitchStartupIntegrationTest {

  /** The name of the test module's bean. */
  private static final String CLIENT = "client";

  /** The test module's switch. */
  private static final String CLIENT_SWITCH = "stratum.http-client.enabled";

  /** A file that switches the test module on, loaded through {@code @PropertySource}. */
  private static final String LATE_SWITCH_FILE = "late-switch/http-client.properties";

  /** Returns an application without a web server, from the given configuration. */
  private SpringApplication application(Class<?> configuration) {
    var application = new SpringApplication(configuration);
    application.setWebApplicationType(WebApplicationType.NONE);
    return application;
  }

  @Nested
  @DisplayName("set before the modules' startup steps run")
  class SetEarly {

    @Test
    @DisplayName("turns the module on")
    void earlySwitchTurnsModuleOn() {
      var application = application(HttpClientModule.class);
      application.setDefaultProperties(Map.of(CLIENT_SWITCH, true));

      try (var context = application.run()) {
        assertThat(context.containsBean(CLIENT)).isTrue();
      }
    }

    @Test
    @DisplayName("as an environment variable, turns on a module whose name has a hyphen")
    void environmentVariableTurnsHyphenatedModuleOn() {
      var environment = new StandardEnvironment();
      environment
          .getPropertySources()
          .addFirst(
              new SystemEnvironmentPropertySource(
                  StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                  Map.of("STRATUM_HTTPCLIENT_ENABLED", "true")));
      var application = application(HttpClientModule.class);
      application.setEnvironment(environment);

      try (var context = application.run()) {
        assertThat(context.containsBean(CLIENT)).isTrue();
      }
    }

    @Test
    @DisplayName(
        "and set again later, as remote configuration does with Spring Cloud's bootstrap,"
            + " still starts the module, because it was already on")
    void switchSetEarlyAndAgainLaterTurnsModuleOn() {
      var application = application(HttpClientModule.class);
      application.setDefaultProperties(Map.of(CLIENT_SWITCH, true));
      application.addInitializers(
          context ->
              context
                  .getEnvironment()
                  .getPropertySources()
                  .addFirst(new MapPropertySource("remote-config", Map.of(CLIENT_SWITCH, true))));

      try (var context = application.run()) {
        assertThat(context.containsBean(CLIENT)).isTrue();
      }
    }

    @Test
    @DisplayName("in a property source that cannot list its keys, turns the module on")
    void switchInUnlistedSourceTurnsModuleOn() {
      var environment = new StandardEnvironment();
      environment.getPropertySources().addFirst(new SingleSwitchSource());
      var application = application(HttpClientModule.class);
      application.setEnvironment(environment);

      try (var context = application.run()) {
        assertThat(context.containsBean(CLIENT)).isTrue();
      }
    }

    @Test
    @DisplayName("with a value that is not a boolean, for a module the service lacks, is ignored")
    void badSwitchOfMissingModuleIsIgnored() {
      var application = application(HttpClientModule.class);
      application.setDefaultProperties(
          Map.of(CLIENT_SWITCH, true, "stratum.cache.enabled", "maybe"));

      try (var context = application.run()) {
        assertThat(context.containsBean(CLIENT)).isTrue();
      }
    }
  }

  @Test
  @DisplayName("only in a @PropertySource file stops startup instead of a half-on module")
  void lateSwitchStopsStartup() {
    assertThatExceptionOfType(Exception.class)
        .isThrownBy(() -> application(LateSwitchedHttpClient.class).run())
        .havingRootCause()
        .isInstanceOf(StratumException.class)
        .withMessageStartingWith("SX-CORE-1006: ")
        .withMessageContaining(CLIENT_SWITCH + " in class path resource [" + LATE_SWITCH_FILE);
  }

  @Test
  @DisplayName("turned off later stops startup instead of a module with defaults but no beans")
  void switchTurnedOffLaterStopsStartup() {
    var application = application(HttpClientModule.class);
    application.setDefaultProperties(Map.of(CLIENT_SWITCH, true));
    application.addInitializers(
        context ->
            context
                .getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("remote-config", Map.of(CLIENT_SWITCH, false))));

    assertThatExceptionOfType(Exception.class)
        .isThrownBy(application::run)
        .havingRootCause()
        .isInstanceOf(StratumException.class)
        .withMessageStartingWith("SX-CORE-1007: ")
        .withMessageContaining(CLIENT_SWITCH + " in remote-config");
  }

  /** A property source that answers only the test module's switch and cannot list its keys. */
  static class SingleSwitchSource extends org.springframework.core.env.PropertySource<Object> {

    /** Names the source. */
    SingleSwitchSource() {
      super("single-switch");
    }

    /**
     * Returns {@code true} for the test module's switch.
     *
     * @param name the property's name
     * @return the value, or {@code null} for any other property
     */
    @Override
    public Object getProperty(String name) {
      return CLIENT_SWITCH.equals(name) ? "true" : null;
    }
  }

  /** A service that switches the test module on only in a {@code @PropertySource} file. */
  @Configuration(proxyBeanMethods = false)
  @PropertySource("classpath:" + LATE_SWITCH_FILE)
  @Import(HttpClientModule.class)
  static class LateSwitchedHttpClient {}

  /** A module switched by {@code stratum.http-client.enabled}, with one bean. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnStratumModule("http-client")
  static class HttpClientModule {

    /**
     * The module's bean.
     *
     * @return a client name
     */
    @Bean
    String client() {
      return "client";
    }
  }
}
