package com.exepex.stratum.observability;

import static com.exepex.stratum.observability.testsupport.ObservabilityTestData.PROBE_PATH;
import static com.exepex.stratum.observability.testsupport.ObservabilityTestData.receivedRequest;
import static com.exepex.stratum.observability.testsupport.ObservabilityTestData.withPath;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.request.HealthProbePaths;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.TracingObservationHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("HealthProbeTracingHandler makes no span for health probes")
class HealthProbeTracingHandlerTest {

  /** The path of an ordinary request. */
  private static final String ORDER_PATH = "/orders";

  private final HealthProbeTracingHandler handler =
      new HealthProbeTracingHandler(new HealthProbePaths(new MockEnvironment()));

  @Test
  @DisplayName("takes a probe request")
  void takesProbeRequest() {
    assertThat(handler.supportsContext(withPath(receivedRequest(), PROBE_PATH))).isTrue();
  }

  @Test
  @DisplayName("leaves other requests to Spring Boot's tracing")
  void leavesOtherRequests() {
    assertThat(handler.supportsContext(withPath(receivedRequest(), ORDER_PATH))).isFalse();
  }

  @Test
  @DisplayName("leaves an outgoing call to a health path, which is not a request the service gets")
  void leavesOutgoingCalls() {
    assertThat(handler.supportsContext(withPath(new Observation.Context(), PROBE_PATH))).isFalse();
  }

  @Test
  @DisplayName("takes a probe whose path is set after it was first asked")
  void takesProbeOncePathIsSet() {
    var context = receivedRequest();
    handler.supportsContext(context);

    assertThat(handler.supportsContext(withPath(context, PROBE_PATH))).isTrue();
  }

  @Test
  @DisplayName("takes every observation made inside a probe")
  void takesObservationsInsideProbe() {
    var registry = ObservationRegistry.create();
    registry.observationConfig().observationHandler(handler);
    var probe = withPath(receivedRequest(), PROBE_PATH);
    var probeObservation = Observation.createNotStarted("probe", () -> probe, registry);
    handler.supportsContext(probe);
    var inside = new Observation.Context();
    inside.setParentObservation(probeObservation);

    assertThat(handler.supportsContext(inside)).isTrue();
  }

  @Test
  @DisplayName("built with no probe paths, takes no probe request, so probes are traced")
  void takesNothingWithNoProbePaths() {
    var tracedProbesHandler = new HealthProbeTracingHandler(HealthProbePaths.none());

    assertThat(tracedProbesHandler.supportsContext(withPath(receivedRequest(), PROBE_PATH)))
        .isFalse();
  }

  @Test
  @DisplayName("leaves the empty tracing context that Spring Boot's timer reads when it stops")
  void leavesEmptyTracingContext() {
    var probe = withPath(receivedRequest(), PROBE_PATH);
    handler.onStart(probe);

    assertThat(probe.containsKey(TracingObservationHandler.TracingContext.class)).isTrue();
    assertThat(handler.getTracingContext(probe).getSpan()).isNull();
  }
}
