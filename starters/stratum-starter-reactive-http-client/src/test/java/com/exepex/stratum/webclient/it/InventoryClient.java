package com.exepex.stratum.webclient.it;

import com.exepex.stratum.test.DownstreamStub;
import org.springframework.web.service.annotation.GetExchange;
import reactor.core.publisher.Mono;

/** The reactive HTTP interface of the downstream inventory service, as a service declares one. */
public interface InventoryClient {

  /**
   * Returns the stock, answered at once.
   *
   * @return the stock
   */
  @GetExchange(DownstreamStub.STOCK_PATH)
  Mono<String> stock();

  /**
   * Returns the stock once the test releases the downstream service.
   *
   * @return the stock
   */
  @GetExchange(DownstreamStub.HELD_PATH)
  Mono<String> heldStock();
}
