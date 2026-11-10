package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;

/**
 * The meters of Stratum's clients: each call, its retries, each breaker's state and each pool. It
 * names no Micrometer type, so the starter runs without Micrometer, with no meters.
 */
@StratumInternal
public interface ClientMeters {

  /**
   * Counts a call that ended: its time by outcome, and its attempts after the first.
   *
   * @param outcome how the call ended
   */
  void callEnded(ClientCallOutcome outcome);

  /**
   * Counts an attempt a client's full pool refused within its {@code max-wait}.
   *
   * @param client the client's name
   */
  void poolRefused(String client);

  /**
   * Shows a pool's size, busy and idle connections and waiting calls from now on.
   *
   * @param name the client's name, or {@code _shared} for the shared pool
   * @param pool the pool
   */
  void poolMade(String name, PoolingHttpClientConnectionManager pool);

  /**
   * Shows a client's breaker state from now on.
   *
   * @param client the client's name
   * @param breaker the breaker
   */
  void breakerMade(String client, CircuitBreaker breaker);
}
