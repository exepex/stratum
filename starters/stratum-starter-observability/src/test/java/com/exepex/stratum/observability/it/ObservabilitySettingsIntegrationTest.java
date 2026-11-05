package com.exepex.stratum.observability.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.logging.LoggingContextConstants;
import com.exepex.stratum.observability.BaggageAllowList;
import com.exepex.stratum.observability.MeterCountGuard;
import com.exepex.stratum.observability.MetricTagGuard;
import com.exepex.stratum.observability.testsupport.BaggageTestData;
import com.exepex.stratum.observability.testsupport.MeterCountTestData;
import com.exepex.stratum.observability.testsupport.ObservabilityTestData;
import io.micrometer.core.instrument.config.MeterFilterReply;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;

@DisplayName("The observability settings work from application.yml and application.properties")
class ObservabilitySettingsIntegrationTest {

  /** A caller's baggage with a tenant, which both files allow, and names they do not allow. */
  private static final String TENANT_BAGGAGE =
      BaggageTestData.CALLER_BAGGAGE + "," + BaggageTestData.REMOTE_FIELD + "=t-1";

  /** The client host cap both files set. */
  private static final int MAX_CLIENT_NAMES_IN_FILES = 3;

  /** More hosts than the files allow. */
  private static final int MANY_HOSTS = 10;

  /** The total cap both files set, twice the default. */
  private static final int MAX_METERS_IN_FILES = 20_000;

  /** Stratum's default total cap, which the files raise. */
  private static final int DEFAULT_MAX_METERS = 10_000;

  /** Checks the settings that both files set the same way. */
  private void assertSettingsApplied(
      MetricTagGuard guard,
      BaggageAllowList allowList,
      MeterCountGuard meterCountGuard,
      Environment environment,
      Resource resource) {
    assertThat(guard.accept(ObservabilityTestData.counterTaggedWith("tenantId")))
        .isEqualTo(MeterFilterReply.DENY);
    assertThat(guard.accept(ObservabilityTestData.counterTaggedWith("orderId")))
        .isEqualTo(MeterFilterReply.NEUTRAL);
    assertThat(BaggageTestData.namesKeptBy(allowList, TENANT_BAGGAGE))
        .containsExactlyInAnyOrder(
            LoggingContextConstants.CORRELATION_ID, BaggageTestData.REMOTE_FIELD);
    var registry = MeterCountTestData.registryGuardedBy(meterCountGuard);
    MeterCountTestData.registerClientTimers(registry, MANY_HOSTS);
    assertThat(MeterCountTestData.clientNamesIn(registry)).hasSize(MAX_CLIENT_NAMES_IN_FILES);
    // The application's own meters share the guard's slots, so the registry holds a little less.
    MeterCountTestData.registerOrderCounters(registry, MAX_METERS_IN_FILES + 1);
    assertThat(registry.getMeters()).hasSizeBetween(DEFAULT_MAX_METERS + 1, MAX_METERS_IN_FILES);
    assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
        .isEqualTo("health,info");
    assertThat(environment.getProperty("management.metrics.tags.environment")).isEqualTo("staging");
    assertThat(resource.getAttribute(AttributeKey.stringKey("service.instance.id")))
        .isEqualTo("orders-file-1");
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

    /** The baggage allow-list the settings configure. */
    @Autowired private BaggageAllowList allowList;

    /** The meter caps the settings configure. */
    @Autowired private MeterCountGuard meterCountGuard;

    /** The application's settings. */
    @Autowired private Environment environment;

    /** What every span and metric says about the service. */
    @Autowired private Resource resource;

    @Test
    @DisplayName(
        "the forbidden and allowed tags, allowed baggage, meter caps, endpoints, environment tag and"
            + " instance ID are applied")
    void settingsApplied() {
      assertSettingsApplied(guard, allowList, meterCountGuard, environment, resource);
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

    /** The baggage allow-list the settings configure. */
    @Autowired private BaggageAllowList allowList;

    /** The meter caps the settings configure. */
    @Autowired private MeterCountGuard meterCountGuard;

    /** The application's settings. */
    @Autowired private Environment environment;

    /** What every span and metric says about the service. */
    @Autowired private Resource resource;

    @Test
    @DisplayName(
        "the forbidden and allowed tags, allowed baggage, meter caps, endpoints, environment tag and"
            + " instance ID are applied")
    void settingsApplied() {
      assertSettingsApplied(guard, allowList, meterCountGuard, environment, resource);
    }
  }
}
