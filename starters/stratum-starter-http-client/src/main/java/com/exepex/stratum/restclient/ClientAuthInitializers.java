package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.httpclient.BasicCredentials;
import com.exepex.stratum.httpclient.ClientAuth;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import lombok.experimental.UtilityClass;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInitializer;

/** Picks the one request initializer that sends a client's credential, from its auth settings. */
@StratumInternal
@UtilityClass
public class ClientAuthInitializers {

  /** The start of a bearer token's header value. */
  private static final String BEARER = "Bearer ";

  /** The start of Basic credentials' header value. */
  private static final String BASIC = "Basic ";

  /**
   * Returns the initializer that sends the credential, or none when the client sets no auth.
   *
   * @param auth the client's auth settings, already validated to hold one kind
   * @return the initializer, if any
   */
  public static Optional<ClientHttpRequestInitializer> initializerFor(ClientAuth auth) {
    if (auth.apiKey() != null) {
      return Optional.of(
          new StaticCredentialInitializer(
              Objects.requireNonNull(auth.apiKeyHeader()), auth.apiKey()));
    }
    if (auth.bearer() != null) {
      return Optional.of(
          new StaticCredentialInitializer(HttpHeaders.AUTHORIZATION, BEARER + auth.bearer()));
    }
    return Optional.ofNullable(auth.basic()).map(ClientAuthInitializers::basicInitializer);
  }

  /** Returns the initializer that sends Basic credentials, encoded once. */
  private static ClientHttpRequestInitializer basicInitializer(BasicCredentials basic) {
    var encoded =
        HttpHeaders.encodeBasicAuth(
            Objects.requireNonNull(basic.username()),
            Objects.requireNonNull(basic.password()),
            StandardCharsets.UTF_8);
    return new StaticCredentialInitializer(HttpHeaders.AUTHORIZATION, BASIC + encoded);
  }
}
