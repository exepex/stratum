package com.exepex.stratum.webmvc;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ServerHttpObservationFilter;

@DisplayName("RequestFailureRecorder marks the request's own observation as failed")
class RequestFailureRecorderTest {

  /** What a request failed with. */
  private static final IllegalStateException FAILURE = new IllegalStateException("broken");

  /** The request. */
  private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders/42");

  /** The failures each observation's handlers were told of, as a span handler is. */
  private final List<Throwable> handledFailures = new CopyOnWriteArrayList<>();

  /** The service's registry, whose handler keeps the failures it is told of. */
  private final ObservationRegistry registry = ObservationRegistry.create();

  /** The request's observation, as Spring's observation filter starts it. */
  private final ServerRequestObservationContext requestContext =
      new ServerRequestObservationContext(request, new MockHttpServletResponse());

  /** Keeps each failure the registry's handlers are told of, and starts the request. */
  @BeforeEach
  void startRequest() {
    registry.observationConfig().observationHandler(new FailureKeepingHandler(handledFailures));
    request.setAttribute(
        ServerHttpObservationFilter.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE, requestContext);
  }

  /** Returns a provider of the registry. */
  private ObjectProvider<ObservationRegistry> registryProvider() {
    var beans = new StaticListableBeanFactory();
    beans.addBean("observationRegistry", registry);
    return beans.getBeanProvider(ObservationRegistry.class);
  }

  /** Records the failure while the observation is current. */
  private void recordWhileCurrent(Observation observation) {
    observation.start();
    try (var _ = observation.openScope()) {
      RequestFailureRecorder.record(registryProvider(), request, FAILURE);
    } finally {
      observation.stop();
    }
  }

  @Test
  @DisplayName("records the failure on the request's observation, so its handlers mark the span")
  void recordsOnRequestObservation() {
    recordWhileCurrent(
        Observation.createNotStarted("http.server.requests", () -> requestContext, registry));

    assertThat(requestContext.getError()).isSameAs(FAILURE);
    assertThat(handledFailures).containsExactly(FAILURE);
  }

  @Test
  @DisplayName("leaves another current observation, such as one the service opened, as it is")
  void leavesOtherObservation() {
    var serviceObservation = Observation.createNotStarted("orders.lookup", registry);

    recordWhileCurrent(serviceObservation);

    assertThat(serviceObservation.getContext().getError()).isNull();
    assertThat(requestContext.getError()).isNull();
    assertThat(handledFailures).isEmpty();
  }

  @Test
  @DisplayName("does nothing without an observation registry")
  void withoutRegistryDoesNothing() {
    RequestFailureRecorder.record(
        new StaticListableBeanFactory().getBeanProvider(ObservationRegistry.class),
        request,
        FAILURE);

    assertThat(requestContext.getError()).isNull();
  }

  /**
   * Keeps the failures it is told of.
   *
   * @param failures where the failures are kept
   */
  private record FailureKeepingHandler(List<Throwable> failures)
      implements ObservationHandler<Observation.Context> {

    /**
     * Keeps the failure.
     *
     * @param context the failed observation's data
     */
    @Override
    public void onError(Observation.Context context) {
      failures.add(context.getError());
    }

    /**
     * Handles every observation.
     *
     * @param context the observation's data
     * @return {@code true}
     */
    @Override
    public boolean supportsContext(Observation.Context context) {
      return true;
    }
  }
}
