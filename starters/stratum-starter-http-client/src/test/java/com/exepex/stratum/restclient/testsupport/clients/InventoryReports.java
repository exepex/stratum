package com.exepex.stratum.restclient.testsupport.clients;

import com.exepex.stratum.httpclient.StratumClient;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import org.springframework.web.service.annotation.GetExchange;

/** A second interface of the inventory client, which shares its group and pool. */
@FunctionalInterface
@StratumClient(RestClientTestData.INVENTORY)
public interface InventoryReports {

  /**
   * Returns a plain answer.
   *
   * @return the body
   */
  @GetExchange(ScriptedDownstream.STOCK)
  String report();
}
