package com.exepex.stratum.logging.testsupport;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.Getter;

/** An observation registry that keeps every observation it finishes, from any thread. */
@Getter
public class FinishedObservations implements ObservationHandler<Observation.Context> {

  /** The registry, whose finished observations are kept. */
  private final ObservationRegistry registry = ObservationRegistry.create();

  /** The finished observations, in the order they finished. */
  private final List<Observation.Context> finished = new CopyOnWriteArrayList<>();

  /** Creates the registry and starts keeping its finished observations. */
  public FinishedObservations() {
    registry.observationConfig().observationHandler(this);
  }

  /**
   * Keeps the finished observation.
   *
   * @param context the observation's context
   */
  @Override
  public void onStop(Observation.Context context) {
    finished.add(context);
  }

  /**
   * Handles every observation.
   *
   * @param context the observation's context
   * @return always {@code true}
   */
  @Override
  public boolean supportsContext(Observation.Context context) {
    return true;
  }
}
