package com.exepex.stratum.webclient.testsupport;

import com.exepex.stratum.test.DownstreamStub;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;

/** Reads Reactor Netty's pool gauges of one downstream stub. */
@UtilityClass
public class PoolGauges {

  /** Reactor Netty's gauge of a pool's limit. */
  private static final String MAX_CONNECTIONS_GAUGE =
      "reactor.netty.connection.provider.max.connections";

  /** Reactor Netty's tag of the pool's name. */
  private static final String NAME_TAG = "name";

  /** Reactor Netty's tag of the downstream service's address. */
  private static final String REMOTE_ADDRESS_TAG = "remote.address";

  /**
   * Returns the connection limit of each pool that has called the stub, by pool name. Reactor Netty
   * reports to the JVM-wide registry, so the pools of other test contexts are left out by address.
   *
   * @param meterRegistry the application's registry
   * @param stub the downstream stub the pools called
   * @return each pool's limit, by its name
   */
  public static Map<String, Double> maxConnectionsByPool(
      MeterRegistry meterRegistry, DownstreamStub stub) {
    var stubPort = ":" + URI.create(stub.baseUrl()).getPort();
    return meterRegistry.find(MAX_CONNECTIONS_GAUGE).gauges().stream()
        .filter(gauge -> gauge.getId().getTag(REMOTE_ADDRESS_TAG).endsWith(stubPort))
        .collect(Collectors.toMap(gauge -> gauge.getId().getTag(NAME_TAG), Gauge::value));
  }
}
