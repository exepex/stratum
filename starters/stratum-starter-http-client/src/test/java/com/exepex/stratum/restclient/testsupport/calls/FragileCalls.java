package com.exepex.stratum.restclient.testsupport.calls;

import com.exepex.stratum.httpclient.StratumClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.service.annotation.GetExchange;

/** The fragile client, whose breaker opens after two failed attempts. */
@StratumClient(CallsTestData.FRAGILE)
public interface FragileCalls {

  /**
   * Gets a page.
   *
   * @return the answer, when it is a success
   */
  @GetExchange("/page")
  ResponseEntity<Void> get();
}
