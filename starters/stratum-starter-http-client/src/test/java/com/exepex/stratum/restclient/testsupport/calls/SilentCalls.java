package com.exepex.stratum.restclient.testsupport.calls;

import com.exepex.stratum.httpclient.StratumClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.service.annotation.GetExchange;

/** The silent client, whose {@code call-log: none} logs no call; it uses the shared pool. */
@FunctionalInterface
@StratumClient(CallsTestData.SILENT)
public interface SilentCalls {

  /**
   * Pings the other service.
   *
   * @return the answer, when it is a success
   */
  @GetExchange("/ping")
  ResponseEntity<Void> ping();
}
