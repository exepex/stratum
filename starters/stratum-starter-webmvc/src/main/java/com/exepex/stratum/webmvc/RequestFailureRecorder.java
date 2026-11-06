package com.exepex.stratum.webmvc;

import com.exepex.stratum.core.api.StratumInternal;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.experimental.UtilityClass;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.filter.ServerHttpObservationFilter;

/**
 * Records a failure that Stratum answers on the request's observation, as WebFlux does for every
 * failure it answers, so the request's timer gets its {@code error} and {@code exception} tags and
 * its span the {@code ERROR} status. Spring MVC records only a failure that leaves the servlet, and
 * Stratum answers the rest itself. Without an observation, as with no Spring Boot actuator, it does
 * nothing.
 */
@StratumInternal
@UtilityClass
public class RequestFailureRecorder {

  /**
   * Records the failure on the current observation, if that is the request's own, and not one the
   * service opened inside it.
   *
   * @param observationRegistry the service's observation registry, if it has one
   * @param request the request that failed
   * @param failure what it failed with
   */
  public static void record(
      ObjectProvider<ObservationRegistry> observationRegistry,
      HttpServletRequest request,
      Throwable failure) {
    var current =
        observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP).getCurrentObservation();
    var requestContext = ServerHttpObservationFilter.findObservationContext(request);
    if (current != null && requestContext.filter(current.getContext()::equals).isPresent()) {
      current.error(failure);
    }
  }
}
