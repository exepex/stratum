package com.exepex.stratum.resilience;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.Severity;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The codes of the resilience module, each listed with its fix in {@code
 * docs/guides/error-codes.md}. {@code SX-RESILIENCE-0001} is a refused request, whose HTTP status
 * is a default of {@code stratum.web.error.statuses}; a code from {@code SX-RESILIENCE-1001} stops
 * the application at startup.
 */
@StratumInternal
@Getter
@RequiredArgsConstructor
public enum ResilienceErrorCode implements ErrorCode {

  /** The request's route already ran as many requests as its limit admits; the caller gets 503. */
  REQUEST_REFUSED(
      "SX-RESILIENCE-0001",
      Severity.ERROR,
      "The service refused the request: its route already has as many requests in progress as it"
          + " admits; send it again later",
      ""),

  /** A route's pattern is not a valid Spring path pattern. */
  INVALID_ROUTE_PATTERN(
      "SX-RESILIENCE-1001",
      Severity.ERROR,
      "A route of " + ResilienceConstants.INCOMING_ROUTES + " is not a valid path pattern",
      "Correct the pattern, or remove the route, or turn the module off with "
          + ResilienceConstants.ENABLED
          + "=false."),

  /** Routes are set in a reactive application, where the limits do not work yet. */
  ROUTES_IN_REACTIVE_APPLICATION(
      "SX-RESILIENCE-1002",
      Severity.ERROR,
      ResilienceConstants.INCOMING_ROUTES
          + " is set, but the limits work only on Spring MVC; WebFlux is planned in "
          + ResilienceConstants.WEBFLUX_ISSUE,
      "Remove the routes from "
          + ResilienceConstants.INCOMING_ROUTES
          + ", or turn the module off with "
          + ResilienceConstants.ENABLED
          + "=false.");

  /** The code. */
  private final String code;

  /** Whether the code is an error or a warning. */
  private final Severity severity;

  /** The fixed description. */
  private final String description;

  /** How to fix the cause of a code that stops startup; empty for a refused request. */
  private final String fix;
}
