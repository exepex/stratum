package com.exepex.stratum.observability;

import com.exepex.stratum.observability.linkedwork.LinkedWork;
import com.exepex.stratum.observability.linkedwork.LinkedWorkContext;
import com.exepex.stratum.observability.linkedwork.LinkedWorkTracingHandler;
import com.exepex.stratum.observability.linkedwork.ReactiveLinkedWork;
import com.exepex.stratum.observability.linkedwork.WorkOrigin;
import com.exepex.stratum.test.StarterTestSupport;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The observability starter follows Stratum's architecture rules")
class ObservabilityArchTest {

  /**
   * The observability starter's types, top-level or nested, that services may use; every other one
   * carries the label.
   */
  private static final Set<Class<?>> API_TYPES =
      Set.of(
          CorrelationIdRecorder.class,
          MeterCountGuard.class,
          MetricTagGuard.class,
          ObservabilityProperties.class,
          ObservabilityProperties.ObservabilityPropertiesBuilder.class,
          ObservabilityProperties.Baggage.class,
          ObservabilityProperties.Tags.class,
          ObservabilityProperties.Tags.TagsBuilder.class,
          ObservabilityProperties.Metrics.class,
          ObservabilityProperties.Metrics.MetricsBuilder.class,
          ObservabilityPropertiesCustomizer.class,
          TelemetryUrlFilter.class,
          LinkedWork.class,
          LinkedWorkContext.class,
          LinkedWorkTracingHandler.class,
          ReactiveLinkedWork.class,
          WorkOrigin.class,
          WorkOrigin.WorkOriginBuilder.class);

  @Test
  @DisplayName(
      "its autoconfigure package only declares beans, it uses no other starter and has no cycles")
  void followsStarterArchitecture() {
    StarterTestSupport.checkStarterArchitecture(ObservabilityConstants.class, API_TYPES);
  }
}
