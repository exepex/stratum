package com.exepex.stratum.core.properties;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("StratumDefaults adds a module's defaults below every value the service sets")
class StratumDefaultsTest {

  private static final String EXPOSURE = "management.endpoints.web.exposure.include";

  private static final String HISTOGRAMS =
      "management.metrics.distribution.percentiles-histogram.all";

  private static final String TASK_CONTEXT_PROPAGATION = "spring.task.execution.propagate-context";

  private static final String REACTOR_CONTEXT_PROPAGATION = "spring.reactor.context-propagation";

  /** The exposure the service chose. */
  private static final String SERVICE_EXPOSURE = "health,loggers";

  private static final Map<String, Object> STRATUM_DEFAULTS =
      Map.of(EXPOSURE, "health,info", HISTOGRAMS, true);

  @Test
  @DisplayName("a value the service set in Java with setDefaultProperties wins; the rest are added")
  void serviceJavaDefaultWins() {
    var environment = new MockEnvironment();
    environment
        .getPropertySources()
        .addLast(
            new MapPropertySource(
                DefaultPropertiesPropertySource.NAME, Map.of(EXPOSURE, SERVICE_EXPOSURE)));

    StratumDefaults.addBelowServiceValues(environment, STRATUM_DEFAULTS);

    assertThat(environment.getProperty(EXPOSURE)).isEqualTo(SERVICE_EXPOSURE);
    assertThat(environment.getProperty(HISTOGRAMS)).isEqualTo("true");
  }

  @Test
  @DisplayName("without default properties, they are created and every other source wins")
  void createsDefaultPropertiesBelowOtherSources() {
    var environment = new MockEnvironment().withProperty(EXPOSURE, SERVICE_EXPOSURE);

    StratumDefaults.addBelowServiceValues(environment, STRATUM_DEFAULTS);

    assertThat(environment.getProperty(EXPOSURE)).isEqualTo(SERVICE_EXPOSURE);
    assertThat(environment.getProperty(HISTOGRAMS)).isEqualTo("true");
    assertThat(
            environment
                .getPropertySources()
                .precedenceOf(
                    environment.getPropertySources().get(DefaultPropertiesPropertySource.NAME)))
        .isEqualTo(environment.getPropertySources().size() - 1);
  }

  @Test
  @DisplayName("by default, Spring's executors and every Reactor operator carry the context")
  void contextCrossesEveryThreadByDefault() {
    var environment = new MockEnvironment();

    assertThat(StratumDefaults.asyncThreadContextPropagation(environment))
        .containsExactlyEntriesOf(Map.of(TASK_CONTEXT_PROPAGATION, true));
    assertThat(StratumDefaults.reactorOperatorContextPropagation(environment))
        .containsExactlyEntriesOf(Map.of(REACTOR_CONTEXT_PROPAGATION, "auto"));
  }

  @Test
  @DisplayName("each Stratum switch, set to false, turns its own propagation back to Spring Boot's")
  void eachSwitchTurnsItsPropagationOff() {
    var environment =
        new MockEnvironment()
            .withProperty("stratum.propagate-context-to-async-threads", "false")
            .withProperty("stratum.propagate-context-to-every-reactor-operator", "false");

    assertThat(StratumDefaults.asyncThreadContextPropagation(environment))
        .containsExactlyEntriesOf(Map.of(TASK_CONTEXT_PROPAGATION, false));
    assertThat(StratumDefaults.reactorOperatorContextPropagation(environment))
        .containsExactlyEntriesOf(Map.of(REACTOR_CONTEXT_PROPAGATION, "limited"));
  }

  @Test
  @DisplayName("a list the service set in Java as indexed keys wins over Stratum's whole value")
  void serviceIndexedListWins() {
    var environment = new MockEnvironment();
    environment
        .getPropertySources()
        .addLast(
            new MapPropertySource(
                DefaultPropertiesPropertySource.NAME,
                Map.of(EXPOSURE + "[0]", "health", EXPOSURE + "[1]", "loggers")));

    StratumDefaults.addBelowServiceValues(environment, STRATUM_DEFAULTS);

    assertThat(Binder.get(environment).bind(EXPOSURE, Bindable.listOf(String.class)).get())
        .containsExactly("health", "loggers");
    assertThat(environment.getProperty(HISTOGRAMS)).isEqualTo("true");
  }
}
