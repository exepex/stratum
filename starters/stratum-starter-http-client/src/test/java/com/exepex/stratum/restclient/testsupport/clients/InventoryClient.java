package com.exepex.stratum.restclient.testsupport.clients;

import com.exepex.stratum.httpclient.StratumClient;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import org.springframework.web.service.annotation.GetExchange;

/** The inventory service's client, with one method per answer of {@link ScriptedDownstream}. */
@StratumClient(RestClientTestData.INVENTORY)
public interface InventoryClient {

  /**
   * Returns a plain answer.
   *
   * @return the body
   */
  @GetExchange(ScriptedDownstream.STOCK)
  String stock();

  /**
   * Returns an answer once the downstream releases it.
   *
   * @return the body
   */
  @GetExchange(ScriptedDownstream.HELD)
  String held();

  /**
   * Calls the path that answers 503.
   *
   * @return never, as the call fails
   */
  @GetExchange(ScriptedDownstream.UNAVAILABLE)
  String unavailable();

  /**
   * Calls the path that answers with a redirect.
   *
   * @return the redirect's empty body
   */
  @GetExchange(ScriptedDownstream.MOVED)
  String moved();

  /**
   * Returns a gzip answer.
   *
   * @return the body
   */
  @GetExchange(ScriptedDownstream.GZIPPED)
  String gzipped();

  /**
   * Returns an answer whose connection is kept alive for 1 second only.
   *
   * @return the body
   */
  @GetExchange(ScriptedDownstream.SHORT_KEEP_ALIVE)
  String shortKeepAlive();
}
