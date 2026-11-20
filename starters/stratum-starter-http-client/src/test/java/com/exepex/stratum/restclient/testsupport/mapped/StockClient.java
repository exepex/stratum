package com.exepex.stratum.restclient.testsupport.mapped;

import com.exepex.stratum.httpclient.StratumClient;
import com.exepex.stratum.httpclient.StratumOperation;
import org.springframework.web.service.annotation.GetExchange;

/** The stock service's client, with two methods that call the same path, one an operation. */
@StratumClient(MappedTestData.STOCK)
public interface StockClient {

  /**
   * Lists the orders as the list-orders operation, whose codes win over the client's.
   *
   * @return the body
   */
  @StratumOperation(MappedTestData.LIST_ORDERS)
  @GetExchange(MappedTestData.ORDERS_PATH)
  String listOrders();

  /**
   * Lists the orders with the client's codes only.
   *
   * @return the body
   */
  @GetExchange(MappedTestData.ORDERS_PATH)
  String orders();
}
