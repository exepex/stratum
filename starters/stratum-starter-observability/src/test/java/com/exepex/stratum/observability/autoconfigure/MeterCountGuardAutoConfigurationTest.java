package com.exepex.stratum.observability.autoconfigure;

import static com.exepex.stratum.observability.testsupport.MeterCountTestData.clientNamesIn;
import static com.exepex.stratum.observability.testsupport.MeterCountTestData.registerClientTimers;
import static com.exepex.stratum.observability.testsupport.MeterCountTestData.registerOrderCounters;
import static com.exepex.stratum.observability.testsupport.MeterCountTestData.registryGuardedBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.observability.MeterCountGuard;
import com.exepex.stratum.observability.ObservabilityConstants;
import com.exepex.stratum.observability.ObservabilityPropertiesCustomizer;
import com.exepex.stratum.observability.testsupport.FewHostsObservabilitySettings;
import com.exepex.stratum.test.StarterTestSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

@DisplayName("ObservabilityAutoConfiguration creates the meter caps, and they back off")
class MeterCountGuardAutoConfigurationTest {

  /** The key of the total cap. */
  private static final String MAX_METERS = ObservabilityConstants.PREFIX + ".metrics.max-meters";

  /** The key of the client host cap. */
  private static final String MAX_CLIENT_NAMES =
      ObservabilityConstants.PREFIX + ".metrics.max-client-names";

  /** The default total cap, owner decision Q5. */
  private static final int DEFAULT_MAX_METERS = 10_000;

  /** The default client host cap, owner decision Q5. */
  private static final int DEFAULT_MAX_CLIENT_NAMES = 100;

  /** The client host cap the tests set in properties. */
  private static final int MAX_CLIENT_NAMES_IN_PROPERTIES = 3;

  /** More hosts than any cap in these tests. */
  private static final int MANY_HOSTS = 2 * DEFAULT_MAX_CLIENT_NAMES;

  /** A service's own guard. */
  private static final MeterCountGuard SERVICE_GUARD =
      new MeterCountGuard(1, 1, ObservabilityConstants.DEFAULT_CLIENT_REQUESTS_NAME);

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ValidationAutoConfiguration.class, ObservabilityAutoConfiguration.class));

  /** Returns how many hosts the context's guard keeps when the service calls many. */
  private int hostsKeptIn(ApplicationContext context) {
    var registry = registryGuardedBy(context.getBean(MeterCountGuard.class));
    registerClientTimers(registry, MANY_HOSTS);
    return clientNamesIn(registry).size();
  }

  @Nested
  @DisplayName("with the module switched on or off")
  class ModuleSwitch {

    @Test
    @DisplayName("with the module on and nothing set, it holds 10,000 meters")
    void createsGuardHoldingDefaultMeters() {
      contextRunner.run(context -> assertThat(metersHeldIn(context)).isEqualTo(DEFAULT_MAX_METERS));
    }

    @Test
    @DisplayName("with the module on and nothing set, it keeps 100 hosts")
    void createsGuardKeepingDefaultHosts() {
      contextRunner.run(
          context -> assertThat(hostsKeptIn(context)).isEqualTo(DEFAULT_MAX_CLIENT_NAMES));
    }

    /** Returns how many meters the context's guard holds when the service makes one too many. */
    private int metersHeldIn(ApplicationContext context) {
      var registry = registryGuardedBy(context.getBean(MeterCountGuard.class));
      registerOrderCounters(registry, DEFAULT_MAX_METERS + 1);
      return registry.getMeters().size();
    }

    @Test
    @DisplayName("with the module off, it is not created")
    void moduleOffCreatesNoGuard() {
      contextRunner
          .withPropertyValues(ObservabilityConstants.ENABLED + "=false")
          .run(context -> assertThat(context).doesNotHaveBean(MeterCountGuard.class));
    }
  }

  @Nested
  @DisplayName("when the service or a company framework has its own guard")
  class BackingOff {

    @Test
    @DisplayName("the service's own guard replaces Stratum's")
    void serviceGuardWins() {
      contextRunner
          .withBean(MeterCountGuard.class, () -> SERVICE_GUARD)
          .run(
              context ->
                  assertThat(context.getBean(MeterCountGuard.class)).isSameAs(SERVICE_GUARD));
    }

    @Test
    @DisplayName("a company framework's auto-configuration with no order wins over Stratum's")
    void companyGuardWins() {
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ValidationAutoConfiguration.class,
                  ObservabilityAutoConfiguration.class,
                  CompanyGuardAutoConfiguration.class))
          .run(
              context ->
                  assertThat(context.getBean(MeterCountGuard.class))
                      .isSameAs(CompanyGuardAutoConfiguration.GUARD));
    }
  }

  @Nested
  @DisplayName("with caps from properties and Java")
  class Settings {

    @Test
    @DisplayName("a cap set in properties replaces the default")
    void propertyCapReplacesDefault() {
      contextRunner
          .withPropertyValues(MAX_CLIENT_NAMES + "=" + MAX_CLIENT_NAMES_IN_PROPERTIES)
          .run(
              context ->
                  assertThat(hostsKeptIn(context)).isEqualTo(MAX_CLIENT_NAMES_IN_PROPERTIES));
    }

    @Test
    @DisplayName("a cap set in a customizer listed in spring.factories wins over properties")
    void javaCapWins(@TempDir Path folder) throws IOException {
      var hostsKept =
          StarterTestSupport.withCustomizers(
              folder,
              ObservabilityPropertiesCustomizer.class,
              List.of(FewHostsObservabilitySettings.class),
              this::hostsKeptWithThreeInProperties);

      assertThat(hostsKept).isEqualTo(FewHostsObservabilitySettings.MAX_CLIENT_NAMES);
    }

    @Test
    @DisplayName("a cap of zero stops startup with SX-CORE-1003, naming the property")
    void zeroCapStopsStartup() {
      contextRunner
          .withPropertyValues(MAX_METERS + "=0")
          .run(
              context ->
                  assertThat(context)
                      .getFailure()
                      .rootCause()
                      .hasMessageStartingWith("SX-CORE-1003")
                      .hasMessageContaining(MAX_METERS));
    }

    /** Starts with three hosts allowed in properties and returns the hosts kept. */
    private int hostsKeptWithThreeInProperties() {
      var hostsKept = new AtomicInteger();
      contextRunner
          .withPropertyValues(MAX_CLIENT_NAMES + "=" + MAX_CLIENT_NAMES_IN_PROPERTIES)
          .run(context -> hostsKept.set(hostsKeptIn(context)));
      return hostsKept.get();
    }
  }

  /** A company framework's auto-configuration that declares no order. */
  @AutoConfiguration
  static class CompanyGuardAutoConfiguration {

    /** The company's guard. */
    static final MeterCountGuard GUARD =
        new MeterCountGuard(1, 1, ObservabilityConstants.DEFAULT_CLIENT_REQUESTS_NAME);

    /**
     * Returns the company's guard.
     *
     * @return the guard
     */
    @Bean
    MeterCountGuard companyMeterCountGuard() {
      return GUARD;
    }
  }
}
