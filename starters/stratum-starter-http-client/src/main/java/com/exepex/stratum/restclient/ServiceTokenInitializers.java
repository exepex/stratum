package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.ClientAuth;
import com.exepex.stratum.httpclient.ClientSettings;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import com.exepex.stratum.httpclient.HttpClientProperties;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.ClientHttpRequestInitializer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

/**
 * Sends the service's own token, of the registration a client names in {@code
 * auth.client-credentials}, on each attempt of its calls. At startup it checks that every such
 * registration exists with the client-credentials grant.
 */
@StratumInternal
public class ServiceTokenInitializers implements TokenInitializers {

  /** The tokens of the registrations, present when Spring Boot has registrations. */
  private final ObjectProvider<SingleFetchTokenManager> tokens;

  /**
   * Makes the initializers, after checking every client's registration.
   *
   * @param properties the settings of every client
   * @param registrations Spring Boot's registrations, absent when none is configured
   * @param tokens the tokens of the registrations, present when Spring Boot has registrations
   * @throws StratumException {@code SX-HTTPC-1005} for a registration that is missing or not of the
   *     client-credentials grant
   */
  public ServiceTokenInitializers(
      HttpClientProperties properties,
      @Nullable ClientRegistrationRepository registrations,
      ObjectProvider<SingleFetchTokenManager> tokens) {
    this.tokens = tokens;
    properties
        .clients()
        .forEach(
            (client, settings) ->
                Optional.ofNullable(settings.auth())
                    .map(ClientAuth::clientCredentials)
                    .ifPresent(registration -> check(client, registration, registrations)));
  }

  /**
   * Returns the initializer that sends the service's token, for a client with {@code
   * client-credentials}. It waits for another caller's fetch at most for the call's time left, or
   * the client's {@code timeout} outside a call of its interface.
   *
   * @param client the client's name
   * @param settings the client's settings
   * @return the initializer, or empty for another kind of auth
   */
  @Override
  public Optional<ClientHttpRequestInitializer> initializerFor(
      String client, ClientSettings settings) {
    var timeout = Objects.requireNonNull(settings.timeout());
    return Optional.ofNullable(settings.auth())
        .map(ClientAuth::clientCredentials)
        .map(registration -> sendToken(client, registration, timeout, tokens.getObject()));
  }

  /** Returns the initializer that sends the registration's token on each attempt. */
  private ClientHttpRequestInitializer sendToken(
      String client, String registration, Duration timeout, SingleFetchTokenManager tokenManager) {
    return request ->
        request
            .getHeaders()
            .setBearerAuth(
                tokenManager
                    .token(client, registration, AttemptTimeLeft.timeLeft().orElse(timeout))
                    .getTokenValue());
  }

  /** Stops startup unless the registration exists with the client-credentials grant. */
  private void check(
      String client, String registration, @Nullable ClientRegistrationRepository registrations) {
    var found =
        Optional.ofNullable(registrations)
            .map(repository -> repository.findByRegistrationId(registration))
            .filter(
                registered ->
                    AuthorizationGrantType.CLIENT_CREDENTIALS.equals(
                        registered.getAuthorizationGrantType()));
    if (found.isEmpty()) {
      throw new StratumException(
          HttpClientErrorCode.REGISTRATION_INVALID,
          HttpClientConstants.CLIENTS
              + "."
              + client
              + ".auth.client-credentials names "
              + registration);
    }
  }
}
