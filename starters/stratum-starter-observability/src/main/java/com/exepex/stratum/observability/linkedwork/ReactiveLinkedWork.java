package com.exepex.stratum.observability.linkedwork;

import io.micrometer.observation.Observation;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * Runs a {@code Mono} or {@code Flux} as linked work, as {@link LinkedWork} does for other work.
 * Each subscription is one run, timed and traced until it completes, fails or is cancelled.
 */
@RequiredArgsConstructor
public class ReactiveLinkedWork {

  /** Gives each run its observation. */
  private final LinkedWork linkedWork;

  /**
   * Runs the {@code Mono} as the named linked work each time it is subscribed.
   *
   * @param workName the work's fixed name, as for {@link LinkedWork#run}
   * @param origins where the work came from, as for {@link LinkedWork#run}
   * @param work the work
   * @param <T> the result's type
   * @return the work, run as linked work
   * @throws IllegalArgumentException if the name is not 1 to 128 safe characters
   */
  public <T> Mono<T> mono(String workName, Collection<WorkOrigin> origins, Mono<T> work) {
    linkedWork.requireSafeName(workName);
    return Mono.deferContextual(
        outerContext -> observeUntilDone(start(workName, origins, outerContext), work));
  }

  /**
   * Runs the {@code Flux} as the named linked work each time it is subscribed.
   *
   * @param workName the work's fixed name, as for {@link LinkedWork#run}
   * @param origins where the work came from, as for {@link LinkedWork#run}
   * @param work the work
   * @param <T> the elements' type
   * @return the work, run as linked work
   * @throws IllegalArgumentException if the name is not 1 to 128 safe characters
   */
  public <T> Flux<T> flux(String workName, Collection<WorkOrigin> origins, Flux<T> work) {
    linkedWork.requireSafeName(workName);
    return Flux.deferContextual(
        outerContext -> observeUntilDone(start(workName, origins, outerContext), work));
  }

  /** Runs the Mono inside the observation, which records a failure and stops when it ends. */
  private <T> Mono<T> observeUntilDone(Observation observation, Mono<T> work) {
    return work.contextWrite(
            context -> context.put(ObservationThreadLocalAccessor.KEY, observation))
        .doOnError(observation::error)
        .doFinally(signal -> observation.stop());
  }

  /** Runs the Flux inside the observation, which records a failure and stops when it ends. */
  private <T> Flux<T> observeUntilDone(Observation observation, Flux<T> work) {
    return work.contextWrite(
            context -> context.put(ObservationThreadLocalAccessor.KEY, observation))
        .doOnError(observation::error)
        .doFinally(signal -> observation.stop());
  }

  /** Starts a run's observation, inside the observation of the Reactor context if it has one. */
  private Observation start(
      String workName, Collection<WorkOrigin> origins, ContextView outerContext) {
    var observation = linkedWork.observation(workName, origins);
    outerContext
        .<Observation>getOrEmpty(ObservationThreadLocalAccessor.KEY)
        .ifPresent(observation::parentObservation);
    return observation.start();
  }
}
