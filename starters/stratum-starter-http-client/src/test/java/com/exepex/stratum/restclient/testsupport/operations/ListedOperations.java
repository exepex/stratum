package com.exepex.stratum.restclient.testsupport.operations;

import com.exepex.stratum.httpclient.StratumOperation;
import org.springframework.web.service.annotation.GetExchange;

/** A generated interface whose method carries the list-orders operation. */
@FunctionalInterface
public interface ListedOperations {

  /**
   * Lists the orders.
   *
   * @return the body
   */
  @StratumOperation("list-orders")
  @GetExchange("/orders")
  String listOrders();
}
