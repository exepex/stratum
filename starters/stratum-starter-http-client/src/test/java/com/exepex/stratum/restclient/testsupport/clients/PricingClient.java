package com.exepex.stratum.restclient.testsupport.clients;

import com.exepex.stratum.httpclient.StratumClient;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import org.springframework.web.service.annotation.GetExchange;

/** The pricing service's client, a second client next to inventory. */
@FunctionalInterface
@StratumClient(RestClientTestData.PRICING)
public interface PricingClient {

  /**
   * Returns a plain answer.
   *
   * @return the body
   */
  @GetExchange(ScriptedDownstream.STOCK)
  String price();
}
