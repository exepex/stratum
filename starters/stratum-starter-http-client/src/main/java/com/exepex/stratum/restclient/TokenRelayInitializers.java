package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.ClientAuth;
import com.exepex.stratum.httpclient.ClientSettings;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import java.util.Optional;
import org.springframework.http.client.ClientHttpRequestInitializer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;

/**
 * Sends the caller's access token on each attempt of the calls of a client with {@code
 * auth.token-relay}: only a token that Spring Security's resource server verified and put in the
 * security context, a JWT or an introspected opaque token. A call without one, such as from a
 * scheduled job, another thread, or a request signed in with an ID token, stops before it is sent.
 */
@StratumInternal
public class TokenRelayInitializers implements TokenInitializers {

  /**
   * Returns the initializer that relays the caller's token, for a client with {@code token-relay}.
   *
   * @param client the client's name
   * @param settings the client's settings
   * @return the initializer, or empty for another kind of auth
   */
  @Override
  public Optional<ClientHttpRequestInitializer> initializerFor(
      String client, ClientSettings settings) {
    return Optional.ofNullable(settings.auth())
        .filter(ClientAuth::isTokenRelay)
        .map(relay -> request -> request.getHeaders().setBearerAuth(callerToken()));
  }

  /**
   * Returns the verified access token of the current caller.
   *
   * @throws StratumException {@code SX-HTTPC-0004} when the caller has none, with no detail, as the
   *     caller's answer shows it; the stack trace in the log shows the call
   */
  private String callerToken() {
    if (SecurityContextHolder.getContext().getAuthentication()
        instanceof AbstractOAuth2TokenAuthenticationToken<?> verified) {
      return verified.getToken().getTokenValue();
    }
    throw new StratumException(HttpClientErrorCode.CALLER_TOKEN_MISSING);
  }
}
