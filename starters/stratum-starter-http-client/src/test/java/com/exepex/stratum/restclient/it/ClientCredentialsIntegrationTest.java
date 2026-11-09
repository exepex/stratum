package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.restclient.ClientResilience;
import com.exepex.stratum.restclient.DownstreamException;
import com.exepex.stratum.restclient.StratumHttpClients;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import com.exepex.stratum.restclient.testsupport.TokenDownstream;
import com.exepex.stratum.restclient.testsupport.TokenDownstream.TokenAnswer;
import com.exepex.stratum.restclient.testsupport.clients.ClientsApplication;
import com.exepex.stratum.restclient.testsupport.clients.InventoryClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The inventory client sends the service's own token of the {@code invoices} registration. Each
 * group has its own identity provider, so that its count of token requests starts at 0.
 */
@SpringBootTest(
    classes = ClientsApplication.class,
    properties =
        RestClientTestData.INVENTORY_CLIENT_CREDENTIALS + "=" + RestClientTestData.REGISTRATION)
@Timeout(60)
@DisplayName("A client with client-credentials sends the service's token, fetched once at a time")
class ClientCredentialsIntegrationTest {

  /** The number of calls sent at once. */
  private static final int CALLS_AT_ONCE = 20;

  /** The number of calls sent one after another. */
  private static final int CALLS_IN_TURN = 3;

  /** The number of calls that wait for a token endpoint that does not answer. */
  private static final int WAITING_CALLS = 5;

  /** The identity provider and the protected service of the outer group. */
  @AutoClose private static final TokenDownstream SERVER = new TokenDownstream();

  @Autowired private InventoryClient inventory;

  /**
   * Points the clients and the registration at the server.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void serverAddresses(DynamicPropertyRegistry registry) {
    RestClientTestData.pointClientsAtTokenServer(registry, SERVER);
  }

  @Test
  @DisplayName("the token goes on every call, fetched once and kept")
  void sendsServiceToken() {
    IntStream.range(0, CALLS_IN_TURN).forEach(call -> inventory.stock());

    var sent = SERVER.authorizations();
    assertThat(sent.getLast()).startsWith("Bearer " + TokenDownstream.TOKEN_PREFIX);
    assertThat(sent.subList(sent.size() - CALLS_IN_TURN, sent.size())).containsOnly(sent.getLast());
    assertThat(SERVER.tokenRequests()).isEqualTo(1);
  }

  @Test
  @DisplayName("the client's ad-hoc calls send the same token")
  void adHocCallSendsToken(@Autowired StratumHttpClients clients) {
    inventory.stock();
    var tokenSent = SERVER.authorizations().getLast();

    clients
        .restClient(RestClientTestData.INVENTORY)
        .get()
        .uri(ScriptedDownstream.STOCK)
        .retrieve()
        .body(String.class);

    assertThat(SERVER.authorizations().getLast()).isEqualTo(tokenSent);
  }

  /** Sends calls of the inventory client at once and returns their results. */
  private List<Future<String>> callAtOnce(int calls) {
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      return threads.invokeAll(
          IntStream.range(0, calls)
              .<Callable<String>>mapToObj(unused -> inventory::stock)
              .toList());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  @Nested
  @DisplayName("when many calls need a token at once")
  class AtOnce {

    /** The identity provider of this group, which has issued no token yet. */
    @AutoClose private static final TokenDownstream FRESH = new TokenDownstream();

    /**
     * Points the clients at this group's server.
     *
     * @param registry the test's properties
     */
    @DynamicPropertySource
    static void freshServer(DynamicPropertyRegistry registry) {
      RestClientTestData.pointClientsAtTokenServer(registry, FRESH);
    }

    @Test
    @DisplayName("one token is fetched and every call sends it")
    void oneFetchForManyCalls() throws ExecutionException, InterruptedException {
      // A loop, as Future.get throws checked exceptions that a lambda could not.
      for (var call : callAtOnce(CALLS_AT_ONCE)) {
        assertThat(call.get()).isEqualTo(ScriptedDownstream.BODY);
      }

      assertThat(FRESH.tokenRequests()).isEqualTo(1);
      assertThat(FRESH.authorizations()).hasSize(CALLS_AT_ONCE).containsOnly("Bearer service-1");
    }
  }

  @Nested
  @DisplayName("when the identity provider refuses the client")
  class Refused {

    /** The identity provider of this group. */
    @AutoClose private static final TokenDownstream REFUSING = new TokenDownstream();

    /**
     * Points the clients at this group's server.
     *
     * @param registry the test's properties
     */
    @DynamicPropertySource
    static void refusingServer(DynamicPropertyRegistry registry) {
      RestClientTestData.pointClientsAtTokenServer(registry, REFUSING);
    }

    @Test
    @DisplayName(
        "the call stops with SX-HTTPC-0002, naming nothing internal; unsent, unretried, "
            + "not a breaker failure")
    void refusedTokenStopsCall(@Autowired ClientResilience resilience) {
      REFUSING.tokenAnswer(TokenAnswer.REFUSE);

      assertThatThrownBy(inventory::stock)
          .isInstanceOf(StratumException.class)
          .hasMessageStartingWith("SX-HTTPC-0002")
          .hasMessageNotContaining(RestClientTestData.INVENTORY)
          .hasMessageNotContaining(RestClientTestData.REGISTRATION)
          .hasMessageNotContaining(RestClientTestData.SECRET);
      assertThat(REFUSING.authorizations()).isEmpty();
      assertThat(REFUSING.tokenRequests()).isEqualTo(1);
      assertThat(
              resilience
                  .circuitBreaker(RestClientTestData.INVENTORY)
                  .orElseThrow()
                  .getMetrics()
                  .getNumberOfFailedCalls())
          .isZero();
    }
  }

  @Nested
  @TestPropertySource(properties = RestClientTestData.INVENTORY_SETTINGS + ".timeout=300ms")
  @DisplayName("when the identity provider does not answer")
  class Held {

    /** The identity provider of this group. */
    @AutoClose private static final TokenDownstream HOLDING = new TokenDownstream();

    /**
     * Points the clients at this group's server.
     *
     * @param registry the test's properties
     */
    @DynamicPropertySource
    static void holdingServer(DynamicPropertyRegistry registry) {
      RestClientTestData.pointClientsAtTokenServer(registry, HOLDING);
    }

    @Test
    @DisplayName("every waiting call fails with SX-HTTPC-0002 within the timeout, after one fetch")
    void heldTokenEndpointFailsFast() {
      HOLDING.tokenAnswer(TokenAnswer.HOLD);
      var started = System.nanoTime();

      callAtOnce(WAITING_CALLS)
          .forEach(
              call ->
                  assertThatThrownBy(call::get)
                      .isInstanceOf(ExecutionException.class)
                      .cause()
                      .isInstanceOf(StratumException.class)
                      .hasMessageStartingWith("SX-HTTPC-0002"));

      assertThat(Duration.ofNanos(System.nanoTime() - started))
          .isLessThan(RestClientTestData.FAILS_WITHIN);
      assertThat(HOLDING.tokenRequests()).isEqualTo(1);
      assertThat(HOLDING.authorizations()).isEmpty();
    }
  }

  @Nested
  @DisplayName("when the other service refuses the token once")
  class RefusedOnce {

    /** The identity provider of this group. */
    @AutoClose private static final TokenDownstream ROTATING = new TokenDownstream();

    /**
     * Points the clients at this group's server.
     *
     * @param registry the test's properties
     */
    @DynamicPropertySource
    static void rotatingServer(DynamicPropertyRegistry registry) {
      RestClientTestData.pointClientsAtTokenServer(registry, ROTATING);
    }

    @Test
    @DisplayName("the call is sent once more with a new token, and succeeds")
    void newTokenAfter401() {
      inventory.stock();
      ROTATING.refuseIssuedTokens();

      assertThat(inventory.stock()).isEqualTo(ScriptedDownstream.BODY);

      assertThat(ROTATING.authorizations())
          .containsExactly("Bearer service-1", "Bearer service-1", "Bearer service-2");
    }
  }

  @Nested
  @DisplayName("when the other service refuses every token")
  class RefusedAlways {

    /** The identity provider of this group. */
    @AutoClose private static final TokenDownstream REFUSING_ALL = new TokenDownstream();

    /**
     * Points the clients at this group's server.
     *
     * @param registry the test's properties
     */
    @DynamicPropertySource
    static void refusingAllServer(DynamicPropertyRegistry registry) {
      RestClientTestData.pointClientsAtTokenServer(registry, REFUSING_ALL);
    }

    @Test
    @DisplayName("the call is sent twice, then the 401 is thrown")
    void second401Thrown() {
      REFUSING_ALL.refuseAllTokens();

      assertThatThrownBy(inventory::stock)
          .isInstanceOfSatisfying(
              DownstreamException.class,
              refused -> assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));

      assertThat(REFUSING_ALL.authorizations()).hasSize(2);
      assertThat(REFUSING_ALL.tokenRequests()).isEqualTo(2);
    }
  }
}
