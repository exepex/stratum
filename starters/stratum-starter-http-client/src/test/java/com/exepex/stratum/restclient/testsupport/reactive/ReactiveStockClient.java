package com.exepex.stratum.restclient.testsupport.reactive;

import com.exepex.stratum.httpclient.StratumClient;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import org.springframework.web.service.annotation.GetExchange;
import reactor.core.publisher.Mono;

/** A client with a reactive method, which the blocking starter cannot send. */
@FunctionalInterface
@StratumClient(RestClientTestData.INVENTORY)
public interface ReactiveStockClient {

  /**
   * Returns the stock later.
   *
   * @return the body, later
   */
  @GetExchange("/stock")
  Mono<String> stock();
}
