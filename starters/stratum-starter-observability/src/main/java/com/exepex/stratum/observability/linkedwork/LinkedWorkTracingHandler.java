package com.exepex.stratum.observability.linkedwork;

import com.exepex.stratum.core.error.LoggingContextConstants;
import io.micrometer.observation.Observation;
import io.micrometer.tracing.Link;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingObservationHandler;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Starts the span of linked work with a link to each origin, instead of as a child of the request
 * that caused it. The span's parent is the work's own surrounding span, such as a scheduled run's,
 * or none. With exactly one origin, the work also keeps that origin's correlation ID, so its log
 * lines can be found with the request's.
 */
@RequiredArgsConstructor
public class LinkedWorkTracingHandler implements TracingObservationHandler<LinkedWorkContext> {

  /** Work from exactly one request keeps that request's correlation ID. */
  private static final int ONE_ORIGIN = 1;

  /** Creates the spans. */
  @Getter private final Tracer tracer;

  /**
   * Starts the work's span, linked to its origins.
   *
   * @param context the work's observation context
   */
  @Override
  public void onStart(LinkedWorkContext context) {
    var spanBuilder = tracer.spanBuilder().name(getSpanName(context));
    var parentSpan = getParentSpan(context);
    if (parentSpan != null) {
      spanBuilder.setParent(parentSpan.context());
    }
    context.getOrigins().forEach(origin -> spanBuilder.addLink(new Link(traceContextOf(origin))));
    getTracingContext(context).setSpan(startWithCorrelationId(spanBuilder, context));
  }

  /**
   * Ends the work's span.
   *
   * @param context the work's observation context
   */
  @Override
  public void onStop(LinkedWorkContext context) {
    var span = getRequiredSpan(context);
    tagSpan(context, span);
    endSpan(context, span);
  }

  /**
   * Returns whether the observation is linked work.
   *
   * @param context an observation's context
   * @return whether it is a {@link LinkedWorkContext}
   */
  @Override
  public boolean supportsContext(Observation.Context context) {
    return context instanceof LinkedWorkContext;
  }

  /**
   * Starts the span, with the single origin's correlation ID in baggage, so the span's context and
   * every scope it opens carry it.
   */
  private Span startWithCorrelationId(Span.Builder spanBuilder, LinkedWorkContext context) {
    if (context.getOrigins().size() != ONE_ORIGIN) {
      return spanBuilder.start();
    }
    try (var _ =
        tracer.createBaggageInScope(
            LoggingContextConstants.CORRELATION_ID,
            context.getOrigins().getFirst().correlationId())) {
      return spanBuilder.start();
    }
  }

  /** Returns the trace context of the request an origin names, for a link. */
  private io.micrometer.tracing.TraceContext traceContextOf(WorkOrigin origin) {
    return tracer
        .traceContextBuilder()
        .traceId(origin.traceId())
        .spanId(origin.spanId())
        .sampled(origin.sampled())
        .build();
  }
}
