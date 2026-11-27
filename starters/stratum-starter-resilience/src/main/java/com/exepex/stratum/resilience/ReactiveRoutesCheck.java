package com.exepex.stratum.resilience;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.StratumException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;

/**
 * Stops the startup of a reactive (WebFlux) application that sets routes, because the limits work
 * only on Spring MVC for now; WebFlux is planned in issue #420. Without routes, it does nothing.
 */
@StratumInternal
@RequiredArgsConstructor
public class ReactiveRoutesCheck implements InitializingBean {

  /** The resilience settings, whose routes it checks. */
  private final ResilienceProperties settings;

  /**
   * Stops startup when routes are set.
   *
   * @throws StratumException {@code SX-RESILIENCE-1002} naming the key and the number of routes
   */
  @Override
  public void afterPropertiesSet() {
    var routes = settings.incoming().routes();
    if (!routes.isEmpty()) {
      throw new StratumException(
          ResilienceErrorCode.ROUTES_IN_REACTIVE_APPLICATION,
          ResilienceConstants.INCOMING_ROUTES + " has " + routes.size() + " route(s)");
    }
  }
}
