package com.exepex.stratum.restclient.testsupport.clients;

import com.exepex.stratum.httpclient.StratumClient;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import java.io.InputStream;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

/** An interface of the pricing client for calls that fail, next to {@link PricingClient}. */
@StratumClient(RestClientTestData.PRICING)
public interface PricingOutage {

  /**
   * Calls the path that answers 503.
   *
   * @return never, as the call fails
   */
  @GetExchange(ScriptedDownstream.UNAVAILABLE)
  String unavailable();

  /**
   * Gets an answer once the downstream releases it.
   *
   * @return the body
   */
  @GetExchange(ScriptedDownstream.HELD)
  String held();

  /**
   * Gets an answer, released by the downstream, as a stream: a streamed download.
   *
   * @return the body as a stream, which the caller closes
   */
  @GetExchange(ScriptedDownstream.HELD)
  InputStream downloadHeld();

  /**
   * Posts an order.
   *
   * @return the body
   */
  @PostExchange(ScriptedDownstream.STOCK)
  String order();
}
