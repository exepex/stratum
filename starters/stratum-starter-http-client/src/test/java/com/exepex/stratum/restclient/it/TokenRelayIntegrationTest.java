package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.restclient.DownstreamException;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import com.exepex.stratum.restclient.testsupport.TokenDownstream;
import com.exepex.stratum.restclient.testsupport.clients.ClientsApplication;
import com.exepex.stratum.restclient.testsupport.clients.InventoryClient;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The inventory client relays the caller's token. Each test puts in the security context what
 * Spring Security would after its own check of the incoming request, and clears it after.
 */
@SpringBootTest(
    classes = ClientsApplication.class,
    properties = RestClientTestData.INVENTORY_SETTINGS + ".auth.token-relay=true")
@DisplayName("A client with token-relay sends only the caller's verified access token")
class TokenRelayIntegrationTest {

  /** The caller's token, which the protected service accepts. */
  private static final String CALLER_TOKEN = TokenDownstream.TOKEN_PREFIX + "caller";

  /** The protected service. */
  @AutoClose private static final TokenDownstream SERVER = new TokenDownstream();

  @Autowired private InventoryClient inventory;

  /**
   * Points the clients at the protected service.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void serverAddresses(DynamicPropertyRegistry registry) {
    RestClientTestData.pointClientsAtTokenServer(registry, SERVER);
  }

  /** Clears the caller of the test. */
  @AfterEach
  void clearCaller() {
    SecurityContextHolder.clearContext();
  }

  /** Puts a caller in the security context of this thread. */
  private void signIn(Authentication caller) {
    SecurityContextHolder.getContext().setAuthentication(caller);
  }

  /** Returns a caller whose JWT the resource server has verified. */
  private JwtAuthenticationToken verifiedCaller(String token) {
    return new JwtAuthenticationToken(
        Jwt.withTokenValue(token).header("alg", "RS256").subject("user-1").build());
  }

  @Test
  @DisplayName("a verified JWT goes in Authorization")
  void relaysVerifiedToken() {
    signIn(verifiedCaller(CALLER_TOKEN));

    assertThat(inventory.stock()).isEqualTo(ScriptedDownstream.BODY);
    assertThat(SERVER.authorizations().getLast()).isEqualTo("Bearer " + CALLER_TOKEN);
  }

  @Test
  @DisplayName("a 401 is passed back as it is, without a second send")
  void refusedTokenPassedBack() {
    signIn(verifiedCaller("expired-caller"));
    var callsBefore = SERVER.authorizations().size();

    assertThatThrownBy(inventory::stock)
        .isInstanceOfSatisfying(
            DownstreamException.class,
            refused -> assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    assertThat(SERVER.authorizations()).hasSize(callsBefore + 1);
    assertThat(SERVER.tokenRequests()).isZero();
  }

  @Nested
  @DisplayName("without a verified access token, the call stops with SX-HTTPC-0004, unsent")
  class NoCallerToken {

    @Test
    @DisplayName("when no one is signed in, as in a scheduled job")
    void noCaller() {
      assertNothingSent(inventory::stock);
    }

    @Test
    @DisplayName("when the caller signed in another way, such as with a password")
    void otherAuthentication() {
      signIn(UsernamePasswordAuthenticationToken.authenticated("user-1", null, List.of()));

      assertNothingSent(inventory::stock);
    }

    @Test
    @DisplayName("when the call runs on another thread, which has no security context")
    void otherThread() {
      signIn(verifiedCaller(CALLER_TOKEN));
      var callsBefore = SERVER.authorizations().size();
      var otherThreadCall = CompletableFuture.supplyAsync(inventory::stock);

      assertThatThrownBy(otherThreadCall::join)
          .isInstanceOf(CompletionException.class)
          .cause()
          .isInstanceOf(StratumException.class)
          .hasMessageStartingWith("SX-HTTPC-0004");
      assertThat(SERVER.authorizations()).hasSize(callsBefore);
    }

    /**
     * Checks that the call stops with SX-HTTPC-0004, naming nothing internal, and sends nothing.
     */
    private void assertNothingSent(Runnable call) {
      var callsBefore = SERVER.authorizations().size();

      assertThatThrownBy(call::run)
          .isInstanceOf(StratumException.class)
          .hasMessageStartingWith("SX-HTTPC-0004")
          .hasMessageNotContaining(RestClientTestData.INVENTORY);
      assertThat(SERVER.authorizations()).hasSize(callsBefore);
    }
  }
}
