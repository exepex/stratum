package com.exepex.stratum.restclient.testsupport.headers;

import com.exepex.stratum.httpclient.StratumClient;
import com.exepex.stratum.restclient.testsupport.EchoDownstream;
import org.springframework.web.service.annotation.GetExchange;

/**
 * A client that names two interceptors of its own, the signing one then the stamping one, which
 * must run in that order, after the global ones in their {@code @Order}.
 */
@FunctionalInterface
@StratumClient(
    value = HeadersTestData.ORDERED,
    interceptors = {SigningInterceptor.class, StampInterceptor.class})
public interface OrderedClient {

  /**
   * Calls the echo path.
   *
   * @return the body
   */
  @GetExchange(EchoDownstream.ECHO)
  String echo();
}
