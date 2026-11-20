package com.exepex.stratum.restclient.testsupport.operations;

import com.exepex.stratum.httpclient.StratumOperation;
import org.springframework.web.service.annotation.GetExchange;

/** A generated interface whose two methods carry the same operation's name. */
public interface TwiceNamedOperations {

  /**
   * Lists the orders.
   *
   * @return the body
   */
  @StratumOperation("list-orders")
  @GetExchange("/orders")
  String listOrders();

  /**
   * Lists the orders of one page.
   *
   * @return the body
   */
  @StratumOperation("list-orders")
  @GetExchange("/orders/page")
  String listOrdersPage();
}
