package com.exepex.stratum.restclient.testsupport.calls;

import com.exepex.stratum.httpclient.StratumClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;

/** The audited client, whose {@code call-log: all} logs every call. */
@FunctionalInterface
@StratumClient(CallsTestData.AUDITED)
public interface AuditedCalls {

  /**
   * Finds an audit entry.
   *
   * @param id the entry's ID
   * @return the answer, when it is a success
   */
  @GetExchange("/audits/{id}")
  ResponseEntity<Void> find(@PathVariable String id);
}
