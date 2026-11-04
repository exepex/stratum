package com.exepex.stratum.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.logging.LoggingContextConstants;
import com.exepex.stratum.observability.BaggageAllowList;
import com.exepex.stratum.observability.ObservabilityConstants;
import com.exepex.stratum.observability.ObservabilityPropertiesCustomizer;
import com.exepex.stratum.observability.testsupport.BaggageTestData;
import com.exepex.stratum.observability.testsupport.TenantBaggageObservabilitySettings;
import com.exepex.stratum.test.StarterTestSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
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

@DisplayName("ObservabilityAutoConfiguration creates the baggage allow-list, and it backs off")
class BaggageAllowListAutoConfigurationTest {

  /** The key of the allowed baggage names. */
  private static final String ALLOWED = ObservabilityConstants.PREFIX + ".baggage.allowed";

  /** A caller's baggage with a remote field, besides the correlation ID and the dropped names. */
  private static final String CALLER_BAGGAGE_WITH_TENANT =
      BaggageTestData.CALLER_BAGGAGE + "," + BaggageTestData.REMOTE_FIELD + "=t-1";

  /** A service's allow-list that keeps nothing. */
  private static final BaggageAllowList SERVICE_ALLOW_LIST = new BaggageAllowList(Set.of());

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ValidationAutoConfiguration.class, ObservabilityAutoConfiguration.class));

  /** Returns the names the context's allow-list keeps from the caller's baggage. */
  private Set<String> namesKeptIn(ApplicationContext context) {
    return BaggageTestData.namesKeptBy(
        context.getBean(BaggageAllowList.class), CALLER_BAGGAGE_WITH_TENANT);
  }

  @Nested
  @DisplayName("with the module switched on or off")
  class ModuleSwitch {

    @Test
    @DisplayName("with the module on and nothing set, it keeps only the correlation ID")
    void createsAllowListKeepingCorrelationId() {
      contextRunner.run(
          context ->
              assertThat(namesKeptIn(context))
                  .containsExactly(LoggingContextConstants.CORRELATION_ID));
    }

    @Test
    @DisplayName("with the module off, it is not created")
    void moduleOffCreatesNoAllowList() {
      contextRunner
          .withPropertyValues(ObservabilityConstants.ENABLED + "=false")
          .run(context -> assertThat(context).doesNotHaveBean(BaggageAllowList.class));
    }
  }

  @Nested
  @DisplayName("when the service or a company framework has its own allow-list")
  class BackingOff {

    @Test
    @DisplayName("the service's own allow-list replaces Stratum's")
    void serviceAllowListWins() {
      contextRunner
          .withBean(BaggageAllowList.class, () -> SERVICE_ALLOW_LIST)
          .run(
              context ->
                  assertThat(context.getBean(BaggageAllowList.class)).isSameAs(SERVICE_ALLOW_LIST));
    }

    @Test
    @DisplayName("a company framework's auto-configuration with no order wins over Stratum's")
    void companyAllowListWins() {
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ValidationAutoConfiguration.class,
                  ObservabilityAutoConfiguration.class,
                  CompanyAllowListAutoConfiguration.class))
          .run(
              context ->
                  assertThat(context.getBean(BaggageAllowList.class))
                      .isSameAs(CompanyAllowListAutoConfiguration.ALLOW_LIST));
    }
  }

  @Nested
  @DisplayName("with baggage names from properties and Java")
  class Settings {

    @Test
    @DisplayName("names set in properties replace the default")
    void propertyNamesReplaceDefault() {
      contextRunner
          .withPropertyValues(ALLOWED + "=" + BaggageTestData.REMOTE_FIELD)
          .run(
              context ->
                  assertThat(namesKeptIn(context)).containsExactly(BaggageTestData.REMOTE_FIELD));
    }

    @Test
    @DisplayName("names set in a customizer listed in spring.factories win over properties")
    void javaNamesWin(@TempDir Path folder) throws IOException {
      var keptNames =
          StarterTestSupport.withCustomizers(
              folder,
              ObservabilityPropertiesCustomizer.class,
              List.of(TenantBaggageObservabilitySettings.class),
              this::namesKeptWithEvilInProperties);

      assertThat(keptNames)
          .containsExactlyInAnyOrder(
              LoggingContextConstants.CORRELATION_ID, BaggageTestData.REMOTE_FIELD);
    }

    @Test
    @DisplayName("a blank name stops startup with SX-CORE-1003, naming the property")
    void blankNameStopsStartup() {
      contextRunner
          .withPropertyValues(ALLOWED + "[0]=")
          .run(
              context ->
                  assertThat(context)
                      .getFailure()
                      .rootCause()
                      .hasMessageStartingWith("SX-CORE-1003")
                      .hasMessageContaining(ALLOWED));
    }

    /** Starts with {@code evil} allowed in properties and returns the names kept. */
    private Set<String> namesKeptWithEvilInProperties() {
      var keptNames = new AtomicReference<Set<String>>();
      contextRunner
          .withPropertyValues(ALLOWED + "=evil")
          .run(context -> keptNames.set(namesKeptIn(context)));
      return keptNames.get();
    }
  }

  /** A company framework's auto-configuration that declares no order. */
  @AutoConfiguration
  static class CompanyAllowListAutoConfiguration {

    /** The company's allow-list. */
    static final BaggageAllowList ALLOW_LIST = new BaggageAllowList(Set.of());

    /**
     * Returns the company's allow-list.
     *
     * @return the allow-list
     */
    @Bean
    // Static, as Spring requires for a post-processor, so it is created before other beans.
    static BaggageAllowList companyBaggageAllowList() {
      return ALLOW_LIST;
    }
  }
}
