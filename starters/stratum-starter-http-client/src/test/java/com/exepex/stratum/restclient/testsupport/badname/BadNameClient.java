package com.exepex.stratum.restclient.testsupport.badname;

import com.exepex.stratum.httpclient.StratumClient;
import org.springframework.web.service.annotation.GetExchange;

/** A client whose name has capitals and an underscore. */
@FunctionalInterface
@StratumClient("Inventory_V2")
public interface BadNameClient {

  /**
   * Returns the stock.
   *
   * @return the body
   */
  @GetExchange("/stock")
  String stock();
}
