package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.testsupport.QueuedDownstream;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import com.exepex.stratum.restclient.testsupport.calls.AuditedCalls;
import com.exepex.stratum.restclient.testsupport.calls.CallsApplication;
import com.exepex.stratum.restclient.testsupport.calls.CallsTestData;
import com.exepex.stratum.restclient.testsupport.calls.FragileCalls;
import com.exepex.stratum.restclient.testsupport.calls.HeldCalls;
import com.exepex.stratum.restclient.testsupport.calls.OrdersCalls;
import com.exepex.stratum.restclient.testsupport.calls.SilentCalls;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = CallsApplication.class)
@TestPropertySource(locations = CallsTestData.SETTINGS)
@Timeout(30)
@DisplayName("Each client's pool shows its use, and each attempt a full pool refused is counted")
class PoolMetersIntegrationTest {

  /** The four gauges Micrometer's binder makes for each pool. */
  private static final List<String> POOL_GAUGES =
      List.of(
          "httpcomponents.httpclient.pool.total.max",
          "httpcomponents.httpclient.pool.total.connections",
          "httpcomponents.httpclient.pool.total.pending",
          "httpcomponents.httpclient.pool.route.max.default");

  /** The longest wait for the first call to reach the other service. */
  private static final Duration REACHED_WITHIN = Duration.ofSeconds(5);

  /** The service the queued clients call. */
  @AutoClose private static final QueuedDownstream DOWNSTREAM = QueuedDownstream.start();

  /** The service the held client calls, which holds its answers until released. */
  @AutoClose private static final ScriptedDownstream HELD = ScriptedDownstream.start();

  @Autowired private OrdersCalls orders;

  @Autowired private AuditedCalls audited;

  @Autowired private SilentCalls silent;

  @Autowired private HeldCalls held;

  @Autowired private FragileCalls unreachable;

  @Autowired private MeterRegistry registry;

  /**
   * Points the clients at their services, and the fragile client at a port that refuses
   * connections.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void addresses(DynamicPropertyRegistry registry) {
    CallsTestData.addresses(
        registry, DOWNSTREAM::url, HELD::url, () -> RestClientTestData.UNUSED_URL);
  }

  /** Returns the attempts a client's full pool refused. */
  private double refused(String client) {
    var counter =
        registry
            .find(HttpClientConstants.POOL_REFUSED_METER)
            .tag(HttpClientConstants.CLIENT_TAG, client)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  @Nested
  @DisplayName("the pool gauges")
  class Gauges {

    @Test
    @DisplayName("two clients' own pools and the shared pool each show four gauges, by client")
    void gaugesPerClient() {
      DOWNSTREAM.answerNext();
      orders.find(CallsTestData.ORDER_ID, CallsTestData.STATUS);
      audited.find(CallsTestData.ORDER_ID);
      silent.ping();

      POOL_GAUGES.forEach(
          gauge ->
              assertThat(
                      registry.find(gauge).gauges().stream()
                          .map(meter -> meter.getId().getTag(HttpClientConstants.CLIENT_TAG))
                          .toList())
                  .as(gauge)
                  .contains(
                      CallsTestData.ORDERS,
                      CallsTestData.AUDITED,
                      HttpClientConstants.SHARED_POOL_TAG)
                  .doesNotContain(CallsTestData.SILENT));
    }
  }

  @Nested
  @DisplayName("the full-pool counter")
  class Refusals {

    @Test
    @DisplayName("with its one connection busy, an attempt that waits past max-wait is counted")
    void poolFullCounted() {
      var before = refused(CallsTestData.HELD);
      var heldBefore = HELD.heldRequests();
      var first = CompletableFuture.supplyAsync(held::held);
      try {
        Awaitility.await().atMost(REACHED_WITHIN).until(() -> HELD.heldRequests() > heldBefore);

        assertThatExceptionOfType(StratumException.class).isThrownBy(held::held);

        assertThat(refused(CallsTestData.HELD)).isEqualTo(before + 1);
      } finally {
        HELD.release();
        first.exceptionally(failure -> null).join();
      }
    }

    @Test
    @DisplayName("an answer timeout and a failed connection are not counted as a full pool")
    void otherFailuresNotCounted() {
      final var before = refused(CallsTestData.HELD);
      assertThatExceptionOfType(StratumException.class).isThrownBy(held::held);
      HELD.release();
      assertThatExceptionOfType(StratumException.class).isThrownBy(unreachable::get);

      assertThat(refused(CallsTestData.HELD)).isEqualTo(before);
      assertThat(refused(CallsTestData.FRAGILE)).isZero();
    }
  }
}
