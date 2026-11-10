package com.exepex.stratum.restclient.testsupport;

import com.exepex.stratum.restclient.ClientCallOutcome;
import com.exepex.stratum.restclient.ClientMeters;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.Getter;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Meters that keep what they were told, for unit tests that check what a call counts. */
@Getter
public class RecordedMeters implements ClientMeters {

  /** The outcome of each call that ended, in order. */
  private final List<ClientCallOutcome> calls = new CopyOnWriteArrayList<>();

  /** The client of each attempt a full pool refused, in order. */
  private final List<String> poolRefusals = new CopyOnWriteArrayList<>();

  /** The name of each pool shown, in order. */
  private final List<String> pools = new CopyOnWriteArrayList<>();

  /** The client of each breaker shown, in order. */
  private final List<String> breakers = new CopyOnWriteArrayList<>();

  @Override
  public void callEnded(ClientCallOutcome outcome) {
    calls.add(outcome);
  }

  @Override
  public void poolRefused(String client) {
    poolRefusals.add(client);
  }

  @Override
  public void poolMade(String name, PoolingHttpClientConnectionManager pool) {
    pools.add(name);
  }

  @Override
  public void breakerMade(String client, CircuitBreaker breaker) {
    breakers.add(client);
  }

  /**
   * Returns these meters as Spring hands them to the starter.
   *
   * @return the provider of these meters
   */
  public ObjectProvider<ClientMeters> provider() {
    var beans = new StaticListableBeanFactory();
    beans.addBean("meters", this);
    return beans.getBeanProvider(ClientMeters.class);
  }
}
