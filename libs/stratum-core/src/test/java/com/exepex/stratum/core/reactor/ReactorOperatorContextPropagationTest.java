package com.exepex.stratum.core.reactor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Hooks;

@DisplayName("ReactorOperatorContextPropagation turns on Reactor's context propagation")
class ReactorOperatorContextPropagationTest {

  private static final String REACTOR_CONTEXT_PROPAGATION = "spring.reactor.context-propagation";

  /** Reactor's switch is global, so every test starts and ends with it off. */
  @BeforeEach
  @AfterEach
  void turnOffReactorContextPropagation() {
    Hooks.disableAutomaticContextPropagation();
  }

  /** Returns an environment with Spring Boot's Reactor setting set to the given value. */
  private static MockEnvironment environmentWithReactorSetting(String reactorSetting) {
    return new MockEnvironment().withProperty(REACTOR_CONTEXT_PROPAGATION, reactorSetting);
  }

  @ParameterizedTest
  @ValueSource(strings = {"auto", "AUTO"})
  @DisplayName("when the setting is auto, in any case, as Spring Boot reads it")
  void turnsOnWhenSetToEveryOperator(String reactorSetting) {
    ReactorOperatorContextPropagation.enableWhenSetToEveryOperator(
        environmentWithReactorSetting(reactorSetting));

    assertThat(Hooks.isAutomaticContextPropagationEnabled()).isTrue();
  }

  @Test
  @DisplayName("not when the setting is limited")
  void staysOffWhenLimited() {
    ReactorOperatorContextPropagation.enableWhenSetToEveryOperator(
        environmentWithReactorSetting("limited"));

    assertThat(Hooks.isAutomaticContextPropagationEnabled()).isFalse();
  }

  @Test
  @DisplayName("not, and without failing, when the service has no Reactor")
  void doesNothingWithoutReactor() throws IOException {
    var thread = Thread.currentThread();
    var serviceClassLoader = thread.getContextClassLoader();
    try (var classLoaderWithoutReactor = new FilteredClassLoader(Hooks.class)) {
      thread.setContextClassLoader(classLoaderWithoutReactor);
      ReactorOperatorContextPropagation.enableWhenSetToEveryOperator(
          environmentWithReactorSetting("auto"));
    } finally {
      thread.setContextClassLoader(serviceClassLoader);
    }

    assertThat(Hooks.isAutomaticContextPropagationEnabled()).isFalse();
  }
}
