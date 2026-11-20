package com.exepex.stratum.restclient.testsupport.operations;

import com.exepex.stratum.httpclient.StratumOperation;
import org.springframework.web.service.annotation.GetExchange;

/** A generated interface whose operation's name has capitals and an underscore. */
@FunctionalInterface
public interface BadNameOperations {

  /**
   * Lists the orders.
   *
   * @return the body
   */
  @StratumOperation("List_Orders")
  @GetExchange("/orders")
  String listOrders();
}
