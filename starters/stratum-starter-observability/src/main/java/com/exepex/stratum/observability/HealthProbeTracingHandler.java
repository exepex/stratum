package com.exepex.stratum.observability;

import com.exepex.stratum.core.api.StratumInternal;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationView;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingObservationHandler;
import java.util.Optional;
import lombok.RequiredArgsConstructor;

/**
 * Makes no span for a health probe request or for any observation inside one, so probes keep their
 * timers but stay out of traces. Ordered before Spring Boot's tracing handlers, so it takes those
 * observations from them.
 */
@StratumInternal
@RequiredArgsConstructor
public final class HealthProbeTracingHandler
    implements TracingObservationHandler<Observation.Context> {

  /** The context entry that marks an observation as part of a probe, read by its children. */
  private static final Class<HealthProbeTracingHandler> PROBE_MARK =
      HealthProbeTracingHandler.class;

  private final HealthProbePaths healthProbePaths;

  /**
   * Returns whether the observation is a probe request or inside one, and marks it if so. The path
   * is set only after the observation is created, so a {@code false} is never kept.
   *
   * @param context the observation's context
   * @return {@code true} for a probe's observation
   */
  @Override
  public boolean supportsContext(Observation.Context context) {
    if (isMarked(context)) {
      return true;
    }
    var probe =
        Optional.ofNullable(context.getParentObservation())
                .map(ObservationView::getContextView)
                .filter(HealthProbeTracingHandler::isMarked)
                .isPresent()
            || isProbeRequest(context);
    if (probe) {
      context.put(PROBE_MARK, Boolean.TRUE);
    }
    return probe;
  }

  /**
   * Leaves an empty tracing context, which Spring Boot's timer handler reads when it stops.
   *
   * @param context the observation's context
   */
  @Override
  public void onStart(Observation.Context context) {
    getTracingContext(context);
  }

  /**
   * Does nothing: there is no span to make current.
   *
   * @param context the observation's context
   */
  @Override
  public void onScopeOpened(Observation.Context context) {
    // No span.
  }

  /**
   * Does nothing: no span was made current.
   *
   * @param context the observation's context
   */
  @Override
  public void onScopeClosed(Observation.Context context) {
    // No span.
  }

  /**
   * Does nothing: no span was made current.
   *
   * @param context the observation's context
   */
  @Override
  public void onScopeReset(Observation.Context context) {
    // No span.
  }

  /**
   * Does nothing: there is no span to record the event on.
   *
   * @param event the event
   * @param context the observation's context
   */
  @Override
  public void onEvent(Observation.Event event, Observation.Context context) {
    // No span.
  }

  /**
   * Does nothing: there is no span to mark as failed.
   *
   * @param context the observation's context
   */
  @Override
  public void onError(Observation.Context context) {
    // No span.
  }

  /**
   * Does nothing: there is no span to end.
   *
   * @param context the observation's context
   */
  @Override
  public void onStop(Observation.Context context) {
    // No span.
  }

  /**
   * Returns a tracer that makes nothing; this handler never starts a span.
   *
   * @return the no-op tracer
   */
  @Override
  public Tracer getTracer() {
    return Tracer.NOOP;
  }

  /** Returns whether the observation is a request the service received on a probe path. */
  private boolean isProbeRequest(Observation.Context context) {
    return context instanceof ReceiverContext<?>
        && Optional.ofNullable(context.getHighCardinalityKeyValue(ObservabilityConstants.HTTP_URL))
            .map(KeyValue::getValue)
            .filter(healthProbePaths::contains)
            .isPresent();
  }

  /** Returns whether the observation was marked as part of a probe. */
  private static boolean isMarked(Observation.ContextView context) {
    return context.get(PROBE_MARK) != null;
  }
}
