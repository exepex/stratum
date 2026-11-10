package com.exepex.stratum.restclient.testsupport.headers;

import com.exepex.stratum.httpclient.StratumClient;
import com.exepex.stratum.restclient.testsupport.EchoDownstream;
import org.springframework.web.service.annotation.GetExchange;

/** A client that names only the audit interceptor, which is global, as its one interceptor. */
@FunctionalInterface
@StratumClient(value = HeadersTestData.LONE, interceptors = AuditInterceptor.class)
public interface LoneClient {

  /**
   * Calls the echo path.
   *
   * @return the body
   */
  @GetExchange(EchoDownstream.ECHO)
  String echo();
}
