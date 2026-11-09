package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import org.springframework.core.Ordered;
import org.springframework.web.service.invoker.HttpExchangeAdapter;

/**
 * Adds behaviour around every call of a Stratum client's interface, such as retries, as a bean.
 * Stratum puts every such bean around each client, the lowest order outermost, so that each call
 * passes through all of them in one fixed order. The orders are in {@code HttpClientConstants}.
 */
@StratumInternal
public interface ClientExchangeDecorator extends Ordered {

  /**
   * Returns the client's calls with this behaviour around them.
   *
   * @param client the client's name
   * @param exchange the client's calls, already wrapped by the decorators of higher order
   * @return the calls with this behaviour around them
   */
  HttpExchangeAdapter decorate(String client, HttpExchangeAdapter exchange);
}
