package com.exepex.stratum.restclient.it;

import com.exepex.stratum.test.DownstreamStub;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

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

  /**
   * Reserves stock, a call that must not reach the downstream service twice; the stub answers 503.
   *
   * @return the answer
   */
  @PostExchange(DownstreamStub.UNAVAILABLE_PATH)
  String reserveStock();
}
