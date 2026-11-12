package com.exepex.stratum.restclient.testsupport;

import com.exepex.stratum.restclient.ClientWarnings;
import com.exepex.stratum.restclient.SingleFetchTokenManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

/**
 * Spring Security's store of the service's tokens, with a stand-in identity provider that issues
 * {@code t-1}, {@code t-2} and so on, as Spring's manager does: a stored token with more than a
 * minute left is returned, else a new one is issued and stored. For unit tests of the token
 * manager, without a server.
 */
public final class IssuingTokenStore implements OAuth2AuthorizedClientService {

  /** How long an issued token lives. */
  public static final Duration LIFETIME = Duration.ofHours(1);

  /** A token's lifetime shorter than the skew, so that it always counts as about to expire. */
  public static final Duration SHORT_LIFETIME = Duration.ofSeconds(30);

  /** A token's time left under which it is fetched again, as Spring Security's own skew. */
  private static final Duration SKEW = Duration.ofSeconds(60);

  /** The registration of the tests. */
  private static final ClientRegistration INVOICES =
      ClientRegistration.withRegistrationId(RestClientTestData.REGISTRATION)
          .clientId("orders")
          .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
          .tokenUri("http://127.0.0.1/token")
          .build();

  /** Spring Security's store, which this one reads and writes through. */
  private final InMemoryOAuth2AuthorizedClientService stored =
      new InMemoryOAuth2AuthorizedClientService(new InMemoryClientRegistrationRepository(INVOICES));

  /** The number of tokens issued. */
  private final AtomicInteger issuedCount = new AtomicInteger();

  /** How long the tokens the stand-in provider issues live. */
  private final AtomicReference<Duration> issuedLifetime = new AtomicReference<>(LIFETIME);

  /** What runs on another thread after the next read of the store, once. */
  private final AtomicReference<@Nullable Runnable> nextReadStep = new AtomicReference<>();

  /** How long the next read waits for what runs after it. */
  private final AtomicReference<Duration> afterNextReadWait = new AtomicReference<>(Duration.ZERO);

  /** The token manager over this store. */
  private final SingleFetchTokenManager tokenManager =
      new SingleFetchTokenManager(
          client -> this::authorize,
          this,
          Clock.systemUTC(),
          new ClientWarnings(Clock.systemUTC()));

  /**
   * Returns the token manager that fetches through this store's identity provider.
   *
   * @return the manager
   */
  public SingleFetchTokenManager manager() {
    return tokenManager;
  }

  /**
   * Makes the stand-in provider issue tokens that live the time given, such as less than the skew.
   *
   * @param lifetime how long each issued token lives
   */
  public void issueFor(Duration lifetime) {
    issuedLifetime.set(lifetime);
  }

  /**
   * Stores a token of the registration, as a fetch of another caller would.
   *
   * @param value the token's value
   * @param lifetime the time left before it expires
   * @return the stored token with its registration
   */
  public OAuth2AuthorizedClient store(String value, Duration lifetime) {
    var now = Instant.now();
    var token =
        new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, value, now, now.plus(lifetime));
    var authorized =
        new OAuth2AuthorizedClient(INVOICES, SingleFetchTokenManager.SERVICE_PRINCIPAL, token);
    saveAuthorizedClient(
        authorized,
        UsernamePasswordAuthenticationToken.unauthenticated(
            SingleFetchTokenManager.SERVICE_PRINCIPAL, null));
    return authorized;
  }

  /**
   * Returns the value of the stored token.
   *
   * @return the value, if a token is stored
   */
  public Optional<String> storedValue() {
    return Optional.ofNullable(
            this.<OAuth2AuthorizedClient>loadAuthorizedClient(
                RestClientTestData.REGISTRATION, SingleFetchTokenManager.SERVICE_PRINCIPAL))
        .map(client -> client.getAccessToken().getTokenValue());
  }

  /**
   * Returns the number of tokens issued.
   *
   * @return the number
   */
  public int issued() {
    return issuedCount.get();
  }

  /**
   * Runs a step on another thread right after the next read of the store, between that read and
   * what the reader does with it, and waits for the step at most the time given.
   *
   * @param step the step, such as another caller's fetch
   * @param wait the longest wait for the step
   */
  public void afterNextRead(Runnable step, Duration wait) {
    afterNextReadWait.set(wait);
    nextReadStep.set(step);
  }

  /** Reads the stored token, then runs the step set for after the next read. */
  @Override
  public <T extends OAuth2AuthorizedClient> @Nullable T loadAuthorizedClient(
      String clientRegistrationId, String principalName) {
    T found = stored.loadAuthorizedClient(clientRegistrationId, principalName);
    var step = nextReadStep.getAndSet(null);
    if (step != null) {
      var other = Thread.ofVirtual().start(step);
      try {
        other.join(afterNextReadWait.get());
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
    }
    return found;
  }

  /** Stores the token, as Spring's manager does after a fetch. */
  @Override
  public void saveAuthorizedClient(
      OAuth2AuthorizedClient authorizedClient, Authentication principal) {
    stored.saveAuthorizedClient(authorizedClient, principal);
  }

  /** Removes the stored token, as the token manager does after a 401. */
  @Override
  public void removeAuthorizedClient(String clientRegistrationId, String principalName) {
    stored.removeAuthorizedClient(clientRegistrationId, principalName);
  }

  /** Returns the stored token while it has more than the skew left, else issues and stores one. */
  private OAuth2AuthorizedClient authorize(OAuth2AuthorizeRequest request) {
    var soon = Instant.now().plus(SKEW);
    return Optional.ofNullable(
            this.<OAuth2AuthorizedClient>loadAuthorizedClient(
                request.getClientRegistrationId(), SingleFetchTokenManager.SERVICE_PRINCIPAL))
        .filter(
            client ->
                Optional.ofNullable(client.getAccessToken().getExpiresAt())
                    .filter(soon::isBefore)
                    .isPresent())
        .orElseGet(() -> store("t-" + issuedCount.incrementAndGet(), issuedLifetime.get()));
  }
}
