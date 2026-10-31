package com.exepex.stratum.observability.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.observability.MetricTagGuard;
import com.exepex.stratum.observability.testsupport.ObservabilityTestData;
import io.micrometer.core.instrument.config.MeterFilterReply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;

@DisplayName("The observability settings work from application.yml and application.properties")
class ObservabilitySettingsIntegrationTest {

  /** Checks the settings that both files set the same way. */
  private void assertSettingsApplied(MetricTagGuard guard, Environment environment) {
    assertThat(guard.accept(ObservabilityTestData.counterTaggedWith("tenantId")))
        .isEqualTo(MeterFilterReply.DENY);
    assertThat(guard.accept(ObservabilityTestData.counterTaggedWith("orderId")))
        .isEqualTo(MeterFilterReply.NEUTRAL);
    assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
        .isEqualTo("health,info");
    assertThat(environment.getProperty("management.metrics.tags.environment")).isEqualTo("staging");
  }

  @Nested
  @SpringBootTest(
      classes = OrdersApplication.class,
      args = "--spring.config.name=observability-yaml",
      properties = {
        "spring.main.web-application-type=none",
        "management.tracing.export.enabled=false",
        "management.otlp.metrics.export.enabled=false"
      })
  // Closes the application after these tests, so its tracing and threads never reach the next.
  @DirtiesContext
  @DisplayName("from application.yml")
  class FromYaml {

    /** The guard the settings configure. */
    @Autowired private MetricTagGuard guard;

    /** The application's settings. */
    @Autowired private Environment environment;

    @Test
    @DisplayName("the forbidden and allowed tags, endpoints and environment tag are applied")
    void settingsApplied() {
      assertSettingsApplied(guard, environment);
    }
  }

  @Nested
  @SpringBootTest(
      classes = OrdersApplication.class,
      args = "--spring.config.name=observability-properties",
      properties = {
        "spring.main.web-application-type=none",
        "management.tracing.export.enabled=false",
        "management.otlp.metrics.export.enabled=false"
      })
  // Closes the application after these tests, so its tracing and threads never reach the next.
  @DirtiesContext
  @DisplayName("from application.properties")
  class FromProperties {

    /** The guard the settings configure. */
    @Autowired private MetricTagGuard guard;

    /** The application's settings. */
    @Autowired private Environment environment;

    @Test
    @DisplayName("the forbidden and allowed tags, endpoints and environment tag are applied")
    void settingsApplied() {
      assertSettingsApplied(guard, environment);
    }
  }
}
