package com.exepex.stratum.restclient.testsupport.calls;

import com.exepex.stratum.httpclient.StratumClient;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import org.springframework.web.service.annotation.GetExchange;

/** The held client, with one connection, a short wait for it and a short answer timeout. */
@FunctionalInterface
@StratumClient(CallsTestData.HELD)
public interface HeldCalls {

  /**
   * Gets an answer the other service holds until the test lets it go.
   *
   * @return the body
   */
  @GetExchange(ScriptedDownstream.HELD)
  String held();
}
