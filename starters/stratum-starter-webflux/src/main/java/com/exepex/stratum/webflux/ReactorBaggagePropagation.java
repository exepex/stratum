package com.exepex.stratum.webflux;

import io.micrometer.context.ContextRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.contextpropagation.ObservationAwareBaggageThreadLocalAccessor;

/**
 * Registers Micrometer's accessor that opens the baggage a request puts in Reactor's context on
 * every thread its steps run on, so the correlation ID reaches the logs and the services called. It
 * is removed when the application stops.
 */
public class ReactorBaggagePropagation implements AutoCloseable {

  /**
   * Starts opening the baggage in Reactor's context.
   *
   * @param observationRegistry the observations, which the baggage is opened inside
   * @param tracer opens the baggage
   */
  public ReactorBaggagePropagation(ObservationRegistry observationRegistry, Tracer tracer) {
    ContextRegistry.getInstance()
        .registerThreadLocalAccessor(
            new ObservationAwareBaggageThreadLocalAccessor(observationRegistry, tracer));
  }

  /** Stops it, when the application stops. */
  @Override
  public void close() {
    ContextRegistry.getInstance()
        .removeThreadLocalAccessor(ObservationAwareBaggageThreadLocalAccessor.KEY);
  }
}
