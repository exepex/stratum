package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.function.SingletonSupplier;

/**
 * Reports what the clients' calls do: the log line and meters of each call that ends, each attempt
 * a full pool refused, and each change of a breaker's state, logged once and shown in the meters.
 */
@Slf4j
@StratumInternal
@RequiredArgsConstructor
public class ClientCallReports {

  /** The log line of each call that ends. */
  private final ClientCallLog callLog;

  /** The meters of the clients, present with Micrometer and a registry. */
  private final ObjectProvider<ClientMeters> meters;

  /**
   * The meters, looked up on first use, not when the clients are made, so that making them does not
   * make the registry; empty without Micrometer.
   */
  private final SingletonSupplier<Optional<ClientMeters>> foundMeters =
      SingletonSupplier.of(this::lookUpMeters);

  /**
   * Writes a call's log line and counts it in the meters.
   *
   * @param outcome how the call ended
   */
  void callEnded(ClientCallOutcome outcome) {
    callLog.write(outcome);
    foundMeters.obtain().ifPresent(clientMeters -> clientMeters.callEnded(outcome));
  }

  /**
   * Counts an attempt a client's full pool refused within its {@code max-wait}.
   *
   * @param client the client's name
   */
  void poolRefused(String client) {
    foundMeters.obtain().ifPresent(clientMeters -> clientMeters.poolRefused(client));
  }

  /**
   * Logs each later change of a client's breaker state once and shows its state in the meters.
   *
   * @param client the client's name
   * @param breaker the client's new breaker
   */
  void breakerMade(String client, CircuitBreaker breaker) {
    breaker.getEventPublisher().onStateTransition(this::logStateChange);
    foundMeters.obtain().ifPresent(clientMeters -> clientMeters.breakerMade(client, breaker));
  }

  /** Returns the meters, when Micrometer and a registry are there. */
  private Optional<ClientMeters> lookUpMeters() {
    return Optional.ofNullable(meters.getIfAvailable());
  }

  /** Logs a change of a breaker's state once, such as from closed to open. */
  private void logStateChange(CircuitBreakerOnStateTransitionEvent event) {
    if (!log.isWarnEnabled()) {
      return;
    }
    var line =
        "The circuit breaker of HTTP client "
            + event.getCircuitBreakerName()
            + " went from "
            + event.getStateTransition().getFromState()
            + " to "
            + event.getStateTransition().getToState();
    // A client's name is checked at startup and holds no line break; SpotBugs only sees this.
    log.warn(line.replaceAll("[\r\n]", " "));
  }
}
