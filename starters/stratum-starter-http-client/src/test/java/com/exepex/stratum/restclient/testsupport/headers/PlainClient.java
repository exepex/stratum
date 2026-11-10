package com.exepex.stratum.restclient.testsupport.headers;

import com.exepex.stratum.httpclient.StratumClient;
import com.exepex.stratum.restclient.testsupport.EchoDownstream;
import java.util.Map;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

/** A client that names no interceptor, so only the global ones run for it. */
@StratumClient(HeadersTestData.PLAIN)
public interface PlainClient {

  /**
   * Calls the echo path.
   *
   * @return the body
   */
  @GetExchange(EchoDownstream.ECHO)
  String echo();

  /**
   * Sends an order as JSON.
   *
   * @param order the order
   * @return the body
   */
  @PostExchange(EchoDownstream.ECHO)
  String send(@RequestBody Map<String, String> order);
}
