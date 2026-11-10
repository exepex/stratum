package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.httpclient.ClientSettings;
import com.exepex.stratum.httpclient.HttpClientProperties;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.util.function.SingletonSupplier;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/**
 * Sets up one Stratum client: its address, connection pool, credential and headers from {@code
 * stratum.http-client.*}, its interceptors, then the service's {@link StratumClientCustomizer}
 * beans. A client set to {@code mock: true} answers from {@link MockAnswers} instead of a pool, and
 * sends no credential. The one place the client interfaces and the client's {@code RestClient} and
 * {@code RestTemplate} beans get their set-up from; the beans also get the client's retries and
 * circuit breaker.
 */
@StratumInternal
@RequiredArgsConstructor
public class StratumClientBuilders {

  /** The settings of every client. */
  private final HttpClientProperties properties;

  /** The pools the clients use. */
  private final ClientConnectionPools pools;

  /** The application's environment, checked for Spring Boot's group settings. */
  private final Environment environment;

  /** The service's changes to the clients' builders. */
  private final ObjectProvider<StratumClientCustomizer> customizers;

  /** The senders of Spring Security's tokens, one per kind whose library is present. */
  private final ObjectProvider<TokenInitializers> tokenInitializers;

  /** The answers of the clients set to {@code mock: true}. */
  private final MockAnswers mockAnswers;

  /** The retries and circuit breakers of the clients, which the beans' calls run through. */
  private final ClientResilience resilience;

  /** The interceptors of each client. */
  private final ClientInterceptors interceptors;

  /**
   * Sets one client's address, pool and credential, after the checks, then applies the customizers.
   * For a client interface, whose calls get their retries around each call instead.
   *
   * @param client the client's name
   * @param builder the client's builder
   * @throws com.exepex.stratum.core.error.StratumException {@code SX-HTTPC-1001} if a Spring Boot
   *     group setting is set for the client, or {@code SX-HTTPC-1004} if it has no address
   */
  public void configure(String client, RestClient.Builder builder) {
    var settings = properties.clientSettings(client);
    builder.baseUrl(checkedUrl(client, settings));
    var transport = transport(settings);
    builder.requestFactory(requestFactory(client, settings, transport));
    transport.credential(client, settings).ifPresent(builder::requestInitializer);
    builder.defaultHeaders(headers -> sentHeaders(settings).forEach(headers::set));
    customize(client, builder);
  }

  /**
   * Sets up the builder of a client's {@code RestClient} bean: as {@link #configure}, with each
   * call run through the client's retries and circuit breaker, and its credential set on each
   * attempt.
   *
   * @param client the client's name
   * @param builder the client's builder
   * @throws com.exepex.stratum.core.error.StratumException {@code SX-HTTPC-1001} if a Spring Boot
   *     group setting is set for the client, or {@code SX-HTTPC-1004} if it has no address
   */
  public void configureBean(String client, RestClient.Builder builder) {
    var settings = properties.clientSettings(client);
    builder
        .baseUrl(checkedUrl(client, settings))
        .requestFactory(resilientRequestFactory(client, settings))
        .defaultHeaders(headers -> sentHeaders(settings).forEach(headers::set));
    customize(client, builder);
  }

  /**
   * Builds a client's {@code RestTemplate} bean: its address, and its calls run through the
   * client's request factory with its retries, circuit breaker and credential, then each
   * customizer's {@link StratumClientCustomizer#customizeRestTemplate}, in their order.
   *
   * @param client the client's name
   * @param builder Spring Boot's builder, with its message converters and customizers
   * @return the client's {@code RestTemplate}
   * @throws com.exepex.stratum.core.error.StratumException {@code SX-HTTPC-1001} if a Spring Boot
   *     group setting is set for the client, or {@code SX-HTTPC-1004} if it has no address
   */
  public RestTemplate restTemplate(String client, RestTemplateBuilder builder) {
    var settings = properties.clientSettings(client);
    var url = checkedUrl(client, settings);
    var requestFactory = resilientRequestFactory(client, settings);
    // The factory is handed over as built, so Spring Boot's own client settings cannot change it.
    var configured =
        sentHeaders(settings).entrySet().stream()
            .reduce(
                builder.baseUri(url).requestFactoryBuilder(_ -> requestFactory),
                (current, header) -> current.defaultHeader(header.getKey(), header.getValue()),
                (outer, inner) -> outer);
    return customizers
        .orderedStream()
        .reduce(
            configured,
            (current, customizer) -> customizer.customizeRestTemplate(client, current),
            (outer, inner) -> outer)
        .build();
  }

  /** Applies each customizer's {@link StratumClientCustomizer#customize}, in their order. */
  private void customize(String client, RestClient.Builder builder) {
    customizers.orderedStream().forEach(customizer -> customizer.customize(client, builder));
  }

  /** Checks a client's settings at startup, and returns its address. */
  private String checkedUrl(String client, ClientSettings settings) {
    StratumClientChecks.checkNoBootSettings(environment, client);
    return StratumClientChecks.requireUrl(client, settings);
  }

  /** Returns the way a client's calls leave the service: its pool, or its mocks. */
  private ClientTransport transport(ClientSettings settings) {
    return ClientTransport.of(settings, pools, tokenInitializers, mockAnswers);
  }

  /** Returns a client's request factory with its retries, breaker and credential on each call. */
  private ClientHttpRequestFactory resilientRequestFactory(String client, ClientSettings settings) {
    var transport = transport(settings);
    return new ResilientRequestFactory(
        client,
        requestFactory(client, settings, transport),
        transport.credential(client, settings).stream().toList(),
        resilience);
  }

  /** Returns the client's headers that send a value: an empty one only drops a default. */
  private Map<String, String> sentHeaders(ClientSettings settings) {
    return settings.headers().entrySet().stream()
        .filter(header -> !header.getValue().isEmpty())
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  /**
   * Returns the request factory of a client's calls: its transport's, inside {@link
   * OutgoingStepsRequestFactory} when the client copies headers or has interceptors, so that those
   * steps run on each request the transport makes, each attempt included. A client with neither
   * gets its transport's factory itself. Used by the interfaces and by the beans alike.
   *
   * @param client the client's name
   * @param settings the client's settings
   * @param transport the client's transport
   * @return the request factory
   */
  ClientHttpRequestFactory requestFactory(
      String client, ClientSettings settings, ClientTransport transport) {
    var factory = transport.requestFactory(client, settings);
    if (settings.copyHeaders().isEmpty() && !interceptors.hasInterceptors(client)) {
      return factory;
    }
    return new OutgoingStepsRequestFactory(
        client,
        factory,
        new CopiedHeaders(settings.copyHeaders()),
        SingletonSupplier.of(() -> interceptors.interceptorsOf(client)),
        Objects.requireNonNull(settings.interceptorBodyLimit()));
  }
}
