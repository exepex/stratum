package com.exepex.stratum.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.test.CpuCostMeter;
import com.exepex.stratum.test.WebTestSupport;
import com.exepex.stratum.web.testsupport.WebTestData;
import java.net.InetAddress;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Catches a blow-up in reading a hostile {@code X-Forwarded-For} header, such as a cost that grows
 * with the square of its length. Each limit is 5 times the cost measured on the build machine, so a
 * 10 times slowdown fails while a CI runner up to about 2.3 times slower passes.
 */
@DisplayName("Reading a hostile X-Forwarded-For header stays inside its CPU time limit")
class ClientIpResolverSpeedTest {

  /** A resolver with the default trusted proxies: the private and loopback ranges. */
  private final ClientIpResolver resolver = new ClientIpResolver(WebTestData.defaults().build());

  /** A load balancer in the service's own network. */
  private static final InetAddress LOAD_BALANCER =
      InetAddress.ofLiteral(WebTestSupport.ipAddress(10, 0, 0, 5));

  /** The client on the internet, at the left end of the header. */
  private static final String CLIENT_ADDRESS = WebTestSupport.ipAddress(198, 51, 100, 20);

  /** The number of trusted hops in the hostile header. */
  private static final int HOPS = 10_000;

  /** A trusted proxy, with the highest port, that the hostile header repeats. */
  private static final String TRUSTED_HOP = WebTestSupport.ipAddress(10, 1, 2, 3) + ":65535";

  /**
   * The client and 10,000 trusted hops with ports, far more than a server's header size limit lets
   * through, so the walk reads every entry.
   */
  private static final List<String> MANY_TRUSTED_HOPS =
      Stream.concat(
              Stream.of(CLIENT_ADDRESS + ":51234"), Collections.nCopies(HOPS, TRUSTED_HOP).stream())
          .toList();

  /**
   * The most CPU time one walk of the 10,000 hops may take: 5 times M, where M = 1,781 µs is the
   * highest of three runs of the module's tests on the build machine, coverage on, JDK 25,
   * 2026-11-23.
   */
  private static final Duration MANY_HOPS_LIMIT = Duration.of(8_905, ChronoUnit.MICROS);

  /** One entry of 100,000 digits with a port, the longest a client can make Stratum read. */
  private static final List<String> LONG_ENTRY = List.of("1".repeat(100_000) + ":80");

  /**
   * The most CPU time one read of the long entry may take: 5 times M, where M = 792 µs is the
   * highest of three runs of the module's tests on the build machine, coverage on, JDK 25,
   * 2026-11-23.
   */
  private static final Duration LONG_ENTRY_LIMIT = Duration.of(3_960, ChronoUnit.MICROS);

  /** Reads in each timed batch: a few milliseconds of work, which averages out noise. */
  private static final int CALLS_PER_BATCH = 20;

  /**
   * Batches run before the timed ones, enough for the JIT compiler to finish the address parsing
   * while the module's other tests keep it busy.
   */
  private static final int WARM_UP_BATCHES = 10;

  @Test
  @DisplayName("10,000 trusted hops with ports are walked within the limit")
  void manyTrustedHopsWithinLimit() {
    assertThat(resolver.clientIp(LOAD_BALANCER, MANY_TRUSTED_HOPS))
        .hasValue(InetAddress.ofLiteral(CLIENT_ADDRESS));

    new CpuCostMeter(CALLS_PER_BATCH, WARM_UP_BATCHES)
        .assertCpuCostWithin(
            "10,000 trusted hops with ports",
            MANY_HOPS_LIMIT,
            () -> resolver.clientIp(LOAD_BALANCER, MANY_TRUSTED_HOPS));
  }

  @Test
  @DisplayName("an entry of 100,000 characters with a port is refused within the limit")
  void longEntryWithinLimit() {
    assertThat(resolver.clientIp(LOAD_BALANCER, LONG_ENTRY)).hasValue(LOAD_BALANCER);

    new CpuCostMeter(CALLS_PER_BATCH, WARM_UP_BATCHES)
        .assertCpuCostWithin(
            "an entry of 100,000 characters",
            LONG_ENTRY_LIMIT,
            () -> resolver.clientIp(LOAD_BALANCER, LONG_ENTRY));
  }
}
