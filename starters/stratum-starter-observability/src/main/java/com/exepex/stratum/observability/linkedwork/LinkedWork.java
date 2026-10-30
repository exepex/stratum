package com.exepex.stratum.observability.linkedwork;

import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.observability.ObservabilityConstants;
import io.micrometer.observation.Observation;
import io.micrometer.observation.Observation.CheckedCallable;
import io.micrometer.observation.Observation.CheckedRunnable;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.BaggageView;
import io.micrometer.tracing.Tracer;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Links work that runs later back to the request that caused it, such as a scheduled job that sends
 * a report an API request asked for. While handling the request, the service stores {@link
 * #captureOrigin()} with the work. When the work runs, {@link #run} gives it its own span, linked
 * to each stored origin, so the trace backend shows which request caused it without joining the
 * request's trace. With exactly one origin, the work's log lines keep the request's correlation ID.
 * Use {@link ReactiveLinkedWork} for a {@code Mono} or {@code Flux}.
 */
@RequiredArgsConstructor
public class LinkedWork {

  /** Times and traces each run. */
  private final ObservationRegistry observationRegistry;

  /** Reads the request's span and correlation ID. */
  private final Tracer tracer;

  /**
   * Returns where work saved now comes from: the current span and correlation ID. Store {@link
   * WorkOrigin#toText()} with the work.
   *
   * @return the origin, or empty outside a traced operation
   */
  public Optional<WorkOrigin> captureOrigin() {
    return Optional.ofNullable(tracer.currentSpan())
        .map(
            span ->
                new WorkOrigin(
                    span.context().traceId(),
                    span.context().spanId(),
                    Boolean.TRUE.equals(span.context().sampled()),
                    correlationIdOrElse(span.context().traceId())));
  }

  /**
   * Runs the work as the named linked work, with a span linked to each origin.
   *
   * @param workName the work's fixed name, such as {@code invoice-export}: 1 to 128 letters,
   *     digits, {@code -}, {@code _}, {@code .} or {@code :}. Never put data in it.
   * @param origins where the work came from, read with {@link WorkOrigin#fromText(String)}; a batch
   *     that serves several requests gives one origin for each
   * @param work the work
   * @param <E> the exception the work may throw
   * @throws E what the work throws, unchanged
   * @throws IllegalArgumentException if the name has another form
   */
  public <E extends Throwable> void run(
      String workName, Collection<WorkOrigin> origins, CheckedRunnable<E> work) throws E {
    observation(workName, origins).observeChecked(work);
  }

  /**
   * Runs the work as the named linked work, with a span linked to each origin, and returns its
   * result.
   *
   * @param workName the work's fixed name; see {@link #run}
   * @param origins where the work came from; see {@link #run}
   * @param work the work
   * @param <T> the result's type
   * @param <E> the exception the work may throw
   * @return what the work returns
   * @throws E what the work throws, unchanged
   * @throws IllegalArgumentException if the name has another form
   */
  public <T, E extends Throwable> @Nullable T call(
      String workName, Collection<WorkOrigin> origins, CheckedCallable<T, E> work) throws E {
    return observation(workName, origins).observeChecked(work);
  }

  /**
   * Returns the timer and span of one run of the named work, tagged with its name only, because
   * each origin would make a new metric series.
   *
   * @param workName the work's fixed name
   * @param origins where the work came from
   * @return the observation, not started
   * @throws IllegalArgumentException if the name is not 1 to 128 safe characters
   */
  Observation observation(String workName, Collection<WorkOrigin> origins) {
    requireSafeName(workName);
    var originList = List.copyOf(origins);
    return Observation.createNotStarted(
            ObservabilityConstants.LINKED_WORK_OBSERVATION,
            () -> new LinkedWorkContext(originList),
            observationRegistry)
        .contextualName(workName)
        .lowCardinalityKeyValue(ObservabilityConstants.LINKED_WORK_TAG, workName);
  }

  /**
   * Refuses a work name that is not 1 to 128 safe characters.
   *
   * @param workName the work's name
   * @throws IllegalArgumentException if the name has another form
   */
  void requireSafeName(String workName) {
    if (!LoggingContextConstants.ID_FORMAT_SAFE_FOR_LOGS.matcher(workName).matches()) {
      throw new IllegalArgumentException(
          "A linked work name must be 1 to 128 letters, digits, '-', '_', '.' or ':'");
    }
  }

  /** Returns the baggage correlation ID, when it is set and safe for logs, or the fallback. */
  private String correlationIdOrElse(String fallback) {
    return Optional.ofNullable(tracer.getBaggage(LoggingContextConstants.CORRELATION_ID))
        .map(BaggageView::get)
        .filter(
            correlationId ->
                LoggingContextConstants.ID_FORMAT_SAFE_FOR_LOGS.matcher(correlationId).matches())
        .orElse(fallback);
  }
}
