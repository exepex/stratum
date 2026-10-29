package com.exepex.stratum.logging.workflow;

import com.exepex.stratum.logging.LoggingConstants;
import io.micrometer.observation.Observation;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * Runs a {@code Mono} or {@code Flux} as a named workflow, as {@link Workflows} does for other
 * work. Each subscription is one run, timed and traced until it completes, fails or is cancelled.
 * The workflow fields travel in the Reactor context, so the lines its operators write can carry
 * them.
 */
@RequiredArgsConstructor
public class ReactiveWorkflows {

  /** Gives each run its fields and its observation. */
  private final Workflows workflows;

  /** Reads the workflow fields of the logging context, for a run subscribed outside Reactor. */
  private final WorkflowFieldsAccessor workflowFields = new WorkflowFieldsAccessor();

  /**
   * Runs the {@code Mono} as the named workflow each time it is subscribed.
   *
   * @param name the workflow's fixed name, as for {@link Workflows}
   * @param workflowId the workflow's ID, or {@code null} for none, as for {@link Workflows}
   * @param work the work
   * @param <T> the result's type
   * @return the work, run as the workflow
   * @throws IllegalArgumentException if the name is not 1 to 128 safe characters
   */
  public <T> Mono<T> mono(String name, @Nullable String workflowId, Mono<T> work) {
    workflows.requireSafeName(name);
    return Mono.deferContextual(outerContext -> runOnce(name, workflowId, work, outerContext));
  }

  /**
   * Runs the {@code Flux} as the named workflow each time it is subscribed.
   *
   * @param name the workflow's fixed name, as for {@link Workflows}
   * @param workflowId the workflow's ID, or {@code null} for none, as for {@link Workflows}
   * @param work the work
   * @param <T> the elements' type
   * @return the work, run as the workflow
   * @throws IllegalArgumentException if the name is not 1 to 128 safe characters
   */
  public <T> Flux<T> flux(String name, @Nullable String workflowId, Flux<T> work) {
    workflows.requireSafeName(name);
    return Flux.deferContextual(outerContext -> runOnce(name, workflowId, work, outerContext));
  }

  /**
   * Returns whether a method with this return type gives a {@code Mono} or {@code Flux}.
   *
   * @param returnType the method's return type
   * @return whether {@link #asWorkflow} applies to its result
   */
  boolean isReactive(Class<?> returnType) {
    return Mono.class.isAssignableFrom(returnType) || Flux.class.isAssignableFrom(returnType);
  }

  /**
   * Runs a method's {@code Mono} or {@code Flux} as the named workflow.
   *
   * @param name the workflow's name
   * @param result what the method returned
   * @return the result, run as the workflow; anything else, such as {@code null}, unchanged
   */
  @Nullable Object asWorkflow(String name, @Nullable Object result) {
    return switch (result) {
      case Mono<?> mono -> mono(name, null, mono);
      case Flux<?> flux -> flux(name, null, flux);
      case null, default -> result;
    };
  }

  /** Runs one subscription of the {@code Mono} as the workflow. */
  private <T> Mono<T> runOnce(
      String name, @Nullable String workflowId, Mono<T> work, ContextView outerContext) {
    var fields = fieldsInside(name, workflowId, outerContext);
    var observation = start(fields, outerContext);
    return work.contextWrite(context -> withWorkflow(context, fields, observation))
        .doOnError(observation::error)
        .doFinally(signal -> observation.stop());
  }

  /** Runs one subscription of the {@code Flux} as the workflow. */
  private <T> Flux<T> runOnce(
      String name, @Nullable String workflowId, Flux<T> work, ContextView outerContext) {
    var fields = fieldsInside(name, workflowId, outerContext);
    var observation = start(fields, outerContext);
    return work.contextWrite(context -> withWorkflow(context, fields, observation))
        .doOnError(observation::error)
        .doFinally(signal -> observation.stop());
  }

  /**
   * Returns the run's fields. The outer workflow's fields come from the Reactor context, or from
   * the logging context when the run is subscribed from code outside Reactor.
   */
  private Map<String, String> fieldsInside(
      String name, @Nullable String workflowId, ContextView outerContext) {
    var outerFields =
        outerContext
            .<Map<String, String>>getOrEmpty(LoggingConstants.WORKFLOW_FIELDS)
            .orElseGet(workflowFields::currentFields);
    return workflows.fieldsInside(name, workflowId, outerFields);
  }

  /** Starts the run's observation, inside the observation of the Reactor context if it has one. */
  private Observation start(Map<String, String> fields, ContextView outerContext) {
    var observation = workflows.observation(fields);
    outerContext
        .<Observation>getOrEmpty(ObservationThreadLocalAccessor.KEY)
        .ifPresent(observation::parentObservation);
    return observation.start();
  }

  /** Puts the run's fields and observation in the context of the work it runs. */
  private Context withWorkflow(
      Context context, Map<String, String> fields, Observation observation) {
    return context
        .put(LoggingConstants.WORKFLOW_FIELDS, fields)
        .put(ObservationThreadLocalAccessor.KEY, observation);
  }
}
