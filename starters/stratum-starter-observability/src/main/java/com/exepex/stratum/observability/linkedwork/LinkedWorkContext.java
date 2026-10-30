package com.exepex.stratum.observability.linkedwork;

import io.micrometer.observation.Observation;
import java.util.List;
import lombok.Getter;

/** The observation context of linked work: the origins its span links back to. */
@Getter
public class LinkedWorkContext extends Observation.Context {

  /** Where the work came from; empty for work that no request caused. */
  private final List<WorkOrigin> origins;

  /**
   * Makes the context of one run of linked work.
   *
   * @param origins where the work came from
   */
  public LinkedWorkContext(List<WorkOrigin> origins) {
    this.origins = List.copyOf(origins);
  }
}
