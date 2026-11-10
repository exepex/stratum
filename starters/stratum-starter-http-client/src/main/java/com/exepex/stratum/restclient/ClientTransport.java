package com.exepex.stratum.restclient;

import com.exepex.stratum.httpclient.ClientAuth;
import com.exepex.stratum.httpclient.ClientSettings;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInitializer;
import org.springframework.web.client.RestClient;

/** How a client's calls reach its service: through its pool, or answered by its mocks. */
sealed interface ClientTransport {

  /**
   * Returns the request factory the client's calls go through: its pool, or its mocks.
   *
   * @param client the client's name
   * @param settings the client's settings
   * @return the request factory
   */
  ClientHttpRequestFactory requestFactory(String client, ClientSettings settings);

  /**
   * Returns what sets the client's credential on each request, when its calls leave the service.
   *
   * @param client the client's name
   * @param settings the client's settings
   * @return the credential's initializer, or empty when the client sends none
   */
  Optional<ClientHttpRequestInitializer> credential(String client, ClientSettings settings);

  /**
   * Sets the client's request factory, and its credential when calls leave the service.
   *
   * @param client the client's name
   * @param settings the client's settings
   * @param builder the client's builder
   */
  default void setUp(String client, ClientSettings settings, RestClient.Builder builder) {
    builder.requestFactory(requestFactory(client, settings));
    credential(client, settings).ifPresent(builder::requestInitializer);
  }

  /**
   * Returns the transport of a client: its mocks with {@code mock: true}, its pool otherwise.
   *
   * @param settings the client's settings
   * @param pools the pools of the clients that call their service
   * @param tokenInitializers the senders of Spring Security's tokens, present with their libraries
   * @param mockAnswers the answers of the mocked clients
   * @return the transport
   */
  static ClientTransport of(
      ClientSettings settings,
      ClientConnectionPools pools,
      ObjectProvider<TokenInitializers> tokenInitializers,
      MockAnswers mockAnswers) {
    return Boolean.TRUE.equals(settings.mock())
        ? new Mocked(mockAnswers)
        : new Pooled(pools, tokenInitializers);
  }

  /** Calls the service through the client's pool, with its credential. */
  @RequiredArgsConstructor
  final class Pooled implements ClientTransport {

    /** The pools of the clients. */
    private final ClientConnectionPools pools;

    /** The senders of Spring Security's tokens, one per kind whose library is present. */
    private final ObjectProvider<TokenInitializers> tokenInitializers;

    @Override
    public ClientHttpRequestFactory requestFactory(String client, ClientSettings settings) {
      return pools.requestFactory(client, settings);
    }

    @Override
    public Optional<ClientHttpRequestInitializer> credential(
        String client, ClientSettings settings) {
      return Optional.ofNullable(settings.auth())
          .flatMap(auth -> authInitializer(client, settings, auth));
    }

    /**
     * Returns the initializer of a client's credential: a Spring Security token, or a fixed one.
     *
     * @throws com.exepex.stratum.core.error.StratumException {@code SX-HTTPC-1006} for a token kind
     *     whose library is missing
     */
    private Optional<ClientHttpRequestInitializer> authInitializer(
        String client, ClientSettings settings, ClientAuth auth) {
      if (auth.clientCredentials() == null && !auth.isTokenRelay()) {
        return ClientAuthInitializers.initializerFor(auth);
      }
      var tokenInitializer =
          tokenInitializers
              .orderedStream()
              .flatMap(kind -> kind.initializerFor(client, settings).stream())
              .findFirst();
      if (tokenInitializer.isEmpty()) {
        throw StratumClientChecks.authLibraryMissing(client);
      }
      return tokenInitializer;
    }
  }

  /** Answers from the client's mocks, with no connection and no credential. */
  @RequiredArgsConstructor
  final class Mocked implements ClientTransport {

    /** The answers of the mocked clients. */
    private final MockAnswers mockAnswers;

    @Override
    public ClientHttpRequestFactory requestFactory(String client, ClientSettings settings) {
      return new MockRequestFactory(
          client, Objects.requireNonNull(settings.timeout()), mockAnswers);
    }

    @Override
    public Optional<ClientHttpRequestInitializer> credential(
        String client, ClientSettings settings) {
      return Optional.empty();
    }
  }
}
