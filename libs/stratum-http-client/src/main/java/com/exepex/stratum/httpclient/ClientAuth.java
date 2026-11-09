package com.exepex.stratum.httpclient;

import java.util.Objects;
import java.util.stream.Stream;
import lombok.Builder;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * How a client proves who it is to the other service: exactly one kind. Take every secret from a
 * placeholder, such as {@code ${GEO_API_KEY}}, never from the file itself.
 *
 * @param apiKey An API key sent in the api-key-header header on every call, such as ${GEO_API_KEY}.
 * @param apiKeyHeader The header that carries the API key, such as Ocp-Apim-Subscription-Key. Only
 *     with api-key. Defaults to X-Api-Key.
 * @param bearer A fixed token sent as Authorization: Bearer on every call, such as
 *     ${PARTNER_TOKEN}.
 * @param basic A user name and password sent with HTTP Basic authentication on every call.
 * @param clientCredentials The registration under spring.security.oauth2.client.registration whose
 *     client-credentials token the service sends as itself, such as invoices. Needs
 *     stratum-starter-oauth2-client.
 * @param tokenRelay Whether to send the caller's access token, as Spring Security's resource server
 *     verified it. A call with no such token stops before it is sent.
 */
@Builder(toBuilder = true)
public record ClientAuth(
    @Nullable String apiKey,
    @Nullable String apiKeyHeader,
    @Nullable String bearer,
    @NestedConfigurationProperty @Nullable BasicCredentials basic,
    @Nullable String clientCredentials,
    @Nullable Boolean tokenRelay) {

  /** Fills the API key's header with Stratum's default when an API key is set without one. */
  public ClientAuth {
    if (apiKey != null && apiKeyHeader == null) {
      apiKeyHeader = HttpClientConstants.DEFAULT_API_KEY_HEADER;
    }
  }

  /**
   * Returns whether exactly one kind is set.
   *
   * @return whether one kind is set
   */
  boolean hasOneKind() {
    return Stream.of(apiKey, bearer, basic, clientCredentials, relayed())
            .filter(Objects::nonNull)
            .count()
        == 1;
  }

  /**
   * Returns whether the caller's token is relayed.
   *
   * @return whether {@code token-relay} is true
   */
  public boolean isTokenRelay() {
    return Boolean.TRUE.equals(tokenRelay);
  }

  /** Returns the relay switch when it is on, or {@code null}, so that false counts as no kind. */
  private @Nullable Boolean relayed() {
    return isTokenRelay() ? Boolean.TRUE : null;
  }

  /**
   * Returns whether the Basic credentials, if set, have both a user name and a password.
   *
   * @return whether Basic is unset or complete
   */
  boolean isBasicComplete() {
    return basic == null || basic.isComplete();
  }

  /**
   * Returns whether a header for the API key is set only together with an API key.
   *
   * @return whether the header has a key
   */
  boolean isApiKeyHeaderWithApiKey() {
    return apiKeyHeader == null || apiKey != null;
  }

  /**
   * Shows which kind is set, never a value, since each one is a secret.
   *
   * @return a text that names the kind only
   */
  @Override
  public String toString() {
    return "ClientAuth[apiKey="
        + (apiKey != null)
        + ", bearer="
        + (bearer != null)
        + ", basic="
        + (basic != null)
        + ", clientCredentials="
        + clientCredentials
        + ", tokenRelay="
        + isTokenRelay()
        + "]";
  }
}
