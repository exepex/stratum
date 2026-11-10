package com.exepex.stratum.restclient.testsupport.calls;

import com.exepex.stratum.httpclient.StratumClient;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;

/** The orders client, which logs its failed calls, as every client does by default. */
@StratumClient(CallsTestData.ORDERS)
public interface OrdersCalls {

  /**
   * Finds an order in a status.
   *
   * @param id the order's ID, such as 8812
   * @param status the status, such as OPEN
   * @return the answer, when it is a success
   */
  @GetExchange(CallsTestData.ORDER_TEMPLATE)
  ResponseEntity<Void> find(@PathVariable String id, @RequestParam String status);

  /**
   * Finds an order, which a 404 answers with nothing.
   *
   * @param id the order's ID
   * @return the order, or empty for a 404
   */
  @GetExchange(CallsTestData.ORDER_TEMPLATE)
  Optional<String> findIfPresent(@PathVariable String id);
}
