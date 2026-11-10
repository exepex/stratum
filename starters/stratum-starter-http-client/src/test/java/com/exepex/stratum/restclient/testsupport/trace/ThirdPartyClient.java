package com.exepex.stratum.restclient.testsupport.trace;

import com.exepex.stratum.httpclient.StratumClient;
import com.exepex.stratum.restclient.testsupport.EchoDownstream;
import org.springframework.web.service.annotation.GetExchange;

/** A client of a third party, whose interceptor sets trace headers of its own. */
@FunctionalInterface
@StratumClient(value = TraceTestData.THIRD_PARTY, interceptors = TraceSettingInterceptor.class)
public interface ThirdPartyClient {

  /**
   * Calls the echo path.
   *
   * @return the body
   */
  @GetExchange(EchoDownstream.ECHO)
  String echo();
}
