package com.exepex.stratum.restclient;

import com.exepex.stratum.httpclient.ClientSettings;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.web.client.RestClient;

/** How a client's calls reach its service: through its pool, or answered by its mocks. */
sealed interface ClientTransport {

  /**
   * Sets the client's request factory, and its credential when calls leave the service.
   *
   * @param client the client's name
   * @param settings the client's settings
   * @param builder the client's builder
   */
  void setUp(String client, ClientSettings settings, RestClient.Builder builder);

  /**
   * Returns the transport of a client: its mocks with {@code mock: true}, its pool otherwise.
   *
   * @param settings the client's settings
   * @param pools the pools of the clients that call their service
   * @param mockAnswers the answers of the mocked clients
   * @return the transport
   */
  static ClientTransport of(
      ClientSettings settings, ClientConnectionPools pools, MockAnswers mockAnswers) {
    return Boolean.TRUE.equals(settings.mock()) ? new Mocked(mockAnswers) : new Pooled(pools);
  }

  /** Calls the service through the client's pool, with its credential. */
  @RequiredArgsConstructor
  final class Pooled implements ClientTransport {

    /** The pools of the clients. */
    private final ClientConnectionPools pools;

    @Override
    public void setUp(String client, ClientSettings settings, RestClient.Builder builder) {
      builder.requestFactory(pools.requestFactory(client, settings));
      Optional.ofNullable(settings.auth())
          .flatMap(ClientAuthInitializers::initializerFor)
          .ifPresent(builder::requestInitializer);
    }
  }

  /** Answers from the client's mocks, with no connection and no credential. */
  @RequiredArgsConstructor
  final class Mocked implements ClientTransport {

    /** The answers of the mocked clients. */
    private final MockAnswers mockAnswers;

    @Override
    public void setUp(String client, ClientSettings settings, RestClient.Builder builder) {
      builder.requestFactory(
          new MockRequestFactory(client, Objects.requireNonNull(settings.timeout()), mockAnswers));
    }
  }
}
