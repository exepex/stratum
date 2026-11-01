package com.exepex.stratum.restclient.it;

import com.exepex.stratum.test.DownstreamStub;
import org.springframework.web.service.annotation.GetExchange;

/** The HTTP interface of the downstream inventory service, as a service declares one. */
public interface InventoryClient {

  /**
   * Returns the stock, answered at once.
   *
   * @return the stock
   */
  @GetExchange(DownstreamStub.STOCK_PATH)
  String stock();

  /**
   * Returns the stock once the test releases the downstream service.
   *
   * @return the stock
   */
  @GetExchange(DownstreamStub.HELD_PATH)
  String heldStock();
}
