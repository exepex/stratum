package com.exepex.stratum.restclient.testsupport.beans;

import com.exepex.stratum.restclient.DownstreamException;
import com.exepex.stratum.restclient.StratumClientCustomizer;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.client.RestClient;

/**
 * A service's customizer with small breakers: the inventory client's judges {@link
 * #INVENTORY_WINDOW} calls, the {@link #LEDGER} client's one call. It counts the error answers it
 * is asked to map, and maps none.
 */
public class GuardRules implements StratumClientCustomizer {

  /** A client whose breaker opens on its first failure. */
  public static final String LEDGER = "ledger";

  /** The calls the inventory client's breaker judges, and needs before it opens. */
  public static final int INVENTORY_WINDOW = 4;

  /** The error answers this customizer was asked to map. */
  private final AtomicInteger mappedErrorCount = new AtomicInteger();

  @Override
  public void customize(String client, RestClient.Builder builder) {
    // The builders keep Stratum's set-up.
  }

  @Override
  public void customizeCircuitBreaker(String client, CircuitBreakerConfig.Builder breaker) {
    if (RestClientTestData.INVENTORY.equals(client)) {
      breaker.slidingWindowSize(INVENTORY_WINDOW).minimumNumberOfCalls(INVENTORY_WINDOW);
    }
    if (LEDGER.equals(client)) {
      breaker.slidingWindowSize(1).minimumNumberOfCalls(1);
    }
  }

  @Override
  public void mapError(String client, DownstreamException failure) {
    mappedErrorCount.incrementAndGet();
  }

  /**
   * Returns how many error answers this customizer was asked to map.
   *
   * @return the number
   */
  public int mappedErrors() {
    return mappedErrorCount.get();
  }
}
