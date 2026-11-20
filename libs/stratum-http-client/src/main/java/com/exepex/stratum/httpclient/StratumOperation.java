package com.exepex.stratum.httpclient;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names one method of a client's interface, so that its settings under {@code
 * stratum.http-client.clients.<name>.operations.<operation>} apply to its calls and win over the
 * client's own.
 *
 * <pre>{@code
 * @StratumClient("inventory")
 * public interface InventoryClient {
 *
 *   @StratumOperation("list-orders")
 *   @GetExchange("/orders")
 *   List<Order> listOrders();
 * }
 * }</pre>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface StratumOperation {

  /**
   * The operation's name, as in {@code operations.<operation>}: lowercase letters, digits and
   * hyphens, such as {@code list-orders}, used once per client.
   *
   * @return the operation's name
   */
  String value();
}
