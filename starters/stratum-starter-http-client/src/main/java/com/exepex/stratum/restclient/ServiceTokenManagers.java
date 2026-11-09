package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.httpclient.HttpClientProperties;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * Makes the Spring Security manager that fetches one client's service tokens: client credentials
 * only, sent through the client's own token pool, and kept in Spring Boot's store of tokens.
 */
@StratumInternal
@RequiredArgsConstructor
public class ServiceTokenManagers implements Function<String, OAuth2AuthorizedClientManager> {

  /** The settings of every client, whose timeouts the token fetch uses. */
  private final HttpClientProperties properties;

  /** The pools, which give each client a token pool of its own. */
  private final ClientConnectionPools pools;

  /** Spring Boot's registrations. */
  private final ClientRegistrationRepository registrations;

  /** Spring Boot's store of fetched tokens, shared by every client. */
  private final OAuth2AuthorizedClientService store;

  /**
   * Makes the manager of one client's tokens.
   *
   * @param client the client's name
   * @return the manager
   */
  @Override
  public OAuth2AuthorizedClientManager apply(String client) {
    var tokenClient = new RestClientClientCredentialsTokenResponseClient();
    // A RestClient of one's own must add the token converters and error handler again, which
    // Spring Security's own client has, or every fetch fails (spike, finding 4).
    tokenClient.setRestClient(
        RestClient.builder()
            .requestFactory(pools.tokenRequestFactory(client, properties.clientSettings(client)))
            .configureMessageConverters(
                converters ->
                    converters
                        .addCustomConverter(new FormHttpMessageConverter())
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
            .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
            .build());
    var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, store);
    manager.setAuthorizedClientProvider(
        OAuth2AuthorizedClientProviderBuilder.builder()
            .clientCredentials(grant -> grant.accessTokenResponseClient(tokenClient))
            .build());
    return manager;
  }
}
