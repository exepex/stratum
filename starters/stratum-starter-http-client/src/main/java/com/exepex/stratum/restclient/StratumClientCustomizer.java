package com.exepex.stratum.restclient;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.web.client.RestClient;

/**
 * Changes the builder of Stratum's clients, for what has no setting, such as a request interceptor
 * or a message converter, their retry and circuit breaker rules, and the exception an error answer
 * of a client interface becomes. Declare it as a bean; every one is called for each client, in
 * their order, after Stratum has set the client's address and connection pool. {@link #customize}
 * changes the client's interfaces and its {@code RestClient} bean, {@link #customizeRestTemplate}
 * its {@code RestTemplate} bean. The builders and the rules are changed once per client; only
 * {@link #mapError} runs per call, on a call of an interface that ends with an error answer.
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
   * Changes the builder of one client's {@code RestTemplate} bean, and returns the builder to use:
   * Spring Boot's builder returns a new one from each method, such as {@code
   * builder.defaultHeader("X-Tenant", "retail")}. Each customizer gets the builder the one before
   * it returned. By default it returns the builder unchanged.
   *
   * @param client the client's name
   * @param builder the builder, with the client's address and connection pool already set
   * @return the builder to use
   */
  default RestTemplateBuilder customizeRestTemplate(String client, RestTemplateBuilder builder) {
    return builder;
  }

  /**
   * Changes one client's retry rules, such as a custom wait or which attempt is sent again. It is
   * called once per client, on its first call, after Stratum has set its rules and its {@code
   * retry} keys, and wins over them; the builder's result type is {@link ClientAttempt}. By default
   * it changes nothing.
   *
   * @param client the client's name
   * @param retry the client's Resilience4j retry rules
   */
  default void customizeRetry(String client, RetryConfig.Builder<ClientAttempt> retry) {
    // Stratum's rules stay as they are.
  }

  /**
   * Changes one client's circuit breaker rules, such as a time-based window. It is called once per
   * client, on its first call, after Stratum has set its rules and its {@code circuit-breaker}
   * keys, and wins over them, and only when the client's {@code circuit-breaker.enabled} is true.
   * By default it changes nothing.
   *
   * @param client the client's name
   * @param breaker the client's Resilience4j circuit breaker rules
   */
  default void customizeCircuitBreaker(String client, CircuitBreakerConfig.Builder breaker) {
    // Stratum's rules stay as they are.
  }

  /**
   * Lets a call of one client that ends with an error answer, after its retries, throw the team's
   * own exception, such as {@code StockNotFoundException} for a 404: throw it here, and return to
   * keep the {@link DownstreamException}. The customizers are asked in their order, so the first
   * that throws wins. The codes of a call that got no answer, such as {@code SX-HTTPC-0001}, are
   * never passed here. By default it keeps the exception.
   *
   * <pre>{@code
   * @Override
   * public void mapError(String client, DownstreamException failure) {
   *   if (client.equals("inventory") && failure.getStatusCode().value() == 404) {
   *     throw new StockNotFoundException(failure);
   *   }
   * }
   * }</pre>
   *
   * @param client the client's name
   * @param failure the error answer, with its status, headers and body
   */
  default void mapError(String client, DownstreamException failure) {
    // The DownstreamException is kept.
  }
}
