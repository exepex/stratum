package com.exepex.stratum.observability;

import com.exepex.stratum.core.api.StratumInternal;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.springframework.util.ErrorHandler;

/**
 * Records a JMS listener's failure on the message's observation, so its timer's {@code error} tag
 * and its span show it, then logs the same warning Spring logs when a container has no error
 * handler. Spring itself records only errors that an error handler rethrows.
 */
@StratumInternal
@RequiredArgsConstructor
public class ObservedJmsErrorHandler implements ErrorHandler {

  /** The warning Spring's listener containers log for a failed message, kept word for word. */
  public static final String LISTENER_FAILED =
      "Execution of JMS message listener failed, and no ErrorHandler has been set.";

  /** The container's registry, whose current observation is the failed message's. */
  private final ObservationRegistry observationRegistry;

  /** The container's own logger, so a team's log level for the container still applies. */
  private final Logger containerLogger;

  /**
   * Marks the current message's observation as failed and logs the warning.
   *
   * @param listenerError the listener's failure
   */
  @Override
  public void handleError(Throwable listenerError) {
    var observation = observationRegistry.getCurrentObservation();
    if (observation != null) {
      observation.error(listenerError);
    }
    containerLogger.warn(LISTENER_FAILED, listenerError);
  }
}
