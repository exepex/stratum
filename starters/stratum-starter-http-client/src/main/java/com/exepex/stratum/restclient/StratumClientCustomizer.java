package com.exepex.stratum.restclient;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.web.client.RestClient;

/**
 * Changes the builder of Stratum's clients, for what has no setting, such as a request interceptor
 * or a message converter, and their retry and circuit breaker rules. Declare it as a bean; every
 * one is called for each client, in their order, after Stratum has set the client's address and
 * connection pool. It runs once per client at startup, never per call.
 *
 * <pre>{@code
 * @Bean
 * StratumClientCustomizer inventoryTenantHeader() {
 *   return (client, builder) -> {
 *     if (client.equals("inventory")) {
 *       builder.defaultHeader("X-Tenant", "retail");
 *     }
 *   };
 * }
 * }</pre>
 */
@FunctionalInterface
public interface StratumClientCustomizer {

  /**
   * Changes one client's builder.
   *
   * @param client the client's name, as in {@code @StratumClient("inventory")}
   * @param builder the client's builder, with its address and connection pool already set
   */
  void customize(String client, RestClient.Builder builder);

  /**
   * Changes one client's retry rules, such as the wait between attempts or which attempt is sent
   * again. It is called once per client, on its first call, after Stratum has set its rules; the
   * builder's result type is {@link ClientAttempt}. By default it changes nothing.
   *
   * @param client the client's name
   * @param retry the client's Resilience4j retry rules
   */
  default void customizeRetry(String client, RetryConfig.Builder<ClientAttempt> retry) {
    // Stratum's rules stay as they are.
  }

  /**
   * Changes one client's circuit breaker rules, such as how many calls it judges or how long it
   * stays open. It is called once per client, on its first call, after Stratum has set its rules,
   * and only when the client's {@code circuit-breaker} is on. By default it changes nothing.
   *
   * @param client the client's name
   * @param breaker the client's Resilience4j circuit breaker rules
   */
  default void customizeCircuitBreaker(String client, CircuitBreakerConfig.Builder breaker) {
    // Stratum's rules stay as they are.
  }
}
