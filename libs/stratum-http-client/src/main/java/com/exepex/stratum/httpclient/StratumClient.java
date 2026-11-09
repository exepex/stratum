package com.exepex.stratum.httpclient;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as a client of another service. Its methods use Spring's own annotations, such
 * as {@code @GetExchange} and {@code @PathVariable}. Stratum finds the interface in the
 * application's packages, makes it a bean and sends its calls to the address in {@code
 * stratum.http-client.clients.<name>.url}, with that client's timeouts and connection pool.
 *
 * <pre>{@code
 * @StratumClient("inventory")
 * public interface InventoryClient {
 *
 *   @GetExchange("/stock/{sku}")
 *   Stock stockFor(@PathVariable String sku);
 * }
 * }</pre>
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface StratumClient {

  /**
   * The client's name, as in {@code stratum.http-client.clients.<name>.*}: lowercase letters,
   * digits and hyphens, starting with a letter, such as {@code inventory} or {@code payments-v2}.
   * Interfaces with the same name share the client's settings and pool.
   *
   * @return the client's name
   */
  String value();
}
