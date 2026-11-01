package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.test.DownstreamStub;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import lombok.experimental.UtilityClass;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.springframework.web.client.ResourceAccessException;

/** Checks that a pool of one connection with a short wait fails a second call fast. */
@UtilityClass
class FullPoolCheck {

  /** The longest the check waits for the held call to reach the stub. */
  private static final Duration WAIT = Duration.ofSeconds(5);

  /** Less than the 2s connect timeout, so the pool's wait is what failed the call. */
  private static final Duration FAILS_WITHIN = Duration.ofMillis(1500);

  /**
   * Holds the only connection with one call, then checks that a second call fails after the pool's
   * wait and that the first still answers.
   *
   * @param inventoryClient the client whose pool has one connection
   * @param inventory the stub the client calls
   */
  static void assertSecondCallFailsFast(InventoryClient inventoryClient, DownstreamStub inventory) {
    final var heldCall = CompletableFuture.supplyAsync(inventoryClient::heldStock);
    await().atMost(WAIT).until(() -> inventory.heldRequests() == 1);
    var startedAt = System.nanoTime();

    assertThatExceptionOfType(ResourceAccessException.class)
        .isThrownBy(inventoryClient::stock)
        .withRootCauseInstanceOf(ConnectionRequestTimeoutException.class);
    assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(FAILS_WITHIN);

    inventory.releaseHeldRequests();
    assertThat(heldCall).succeedsWithin(WAIT).isEqualTo(DownstreamStub.STOCK_BODY);
  }
}
