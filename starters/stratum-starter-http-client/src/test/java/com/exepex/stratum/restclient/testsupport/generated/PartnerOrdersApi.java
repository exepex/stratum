package com.exepex.stratum.restclient.testsupport.generated;

import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import org.springframework.web.service.annotation.GetExchange;

/**
 * A partner's interface as an OpenAPI generator writes it: Spring's annotations only, no
 * {@code @StratumClient}. It joins the partner client through the client's {@code interfaces}
 * setting.
 */
public interface PartnerOrdersApi {

  /**
   * Returns a plain answer.
   *
   * @return the body
   */
  @GetExchange(ScriptedDownstream.STOCK)
  String orders();

  /**
   * Returns an answer once the downstream releases it.
   *
   * @return the body
   */
  @GetExchange(ScriptedDownstream.HELD)
  String heldOrders();
}
