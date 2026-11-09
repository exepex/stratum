package com.exepex.stratum.restclient;

import org.springframework.web.client.RestClient;

/**
 * Changes the builder of Stratum's clients, for what has no setting, such as a request interceptor
 * or a message converter. Declare it as a bean; every one is called for each client, in their
 * order, after Stratum has set the client's address and connection pool. It runs once per client at
 * startup, never per call.
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
}
