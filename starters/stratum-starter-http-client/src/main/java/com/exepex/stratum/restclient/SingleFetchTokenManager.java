package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/**
 * Gives each client-credentials registration's token to every call, fetching it at most once at a
 * time. A valid token in the store is returned without a lock. Otherwise the first caller fetches
 * it on its own thread, through its client's token pool, and every other caller waits for that same
 * fetch, at most for the time it has left, and gets the same token or the same failure. The token
 * is kept for the service, never for a signed-in user, so one token serves every call.
 */
@Slf4j
@StratumInternal
@RequiredArgsConstructor
public class SingleFetchTokenManager {

  /** The principal the service's own tokens are stored under. */
  public static final String SERVICE_PRINCIPAL = "stratum-service";

  /** How long before its expiry a token is fetched again, as Spring Security's own skew. */
  private static final Duration EXPIRY_SKEW = Duration.ofSeconds(60);

  /**
   * Makes the Spring Security manager that fetches a client's tokens through the client's own token
   * pool and stores them in {@link #store}.
   */
  private final Function<String, OAuth2AuthorizedClientManager> newManager;

  /** Spring Security's store of fetched tokens. */
  private final OAuth2AuthorizedClientService store;

  /** Tells the current time, against which a token's expiry is read. */
  private final Clock clock;

  /** Limits the warning of a failed fetch to one per client per minute. */
  private final ClientWarnings warnings;

  /** The manager of each client, made on its first fetch. */
  private final Map<String, OAuth2AuthorizedClientManager> managers = new ConcurrentHashMap<>();

  /** The token an attempt sends, fetched before its request is made; bound only for the attempt. */
  private static final ScopedValue<AttemptToken> ATTEMPT_TOKEN = ScopedValue.newInstance();

  /** The fetch running now for each registration, removed when it ends; drops run as one too. */
  private final Map<String, CompletableFuture<OAuth2AccessToken>> fetches =
      new ConcurrentHashMap<>();

  /**
   * Returns the token of the registration: the one the current attempt was given, else a valid
   * token from the store, fetched if the store has none.
   *
   * @param client the client's name, for the failure and its warning
   * @param registration the registration's name
   * @param waitLimit the longest wait for another caller's fetch
   * @return the token
   * @throws InterceptorFailure carrying {@code SX-HTTPC-0002} when the token cannot be fetched in
   *     time: nothing was sent, so the breaker ignores the call, and the outer layer of the call
   *     throws the {@code SX-HTTPC-0002} itself
   */
  public OAuth2AccessToken token(String client, String registration, Duration waitLimit) {
    try {
      return attemptToken(registration)
          .or(() -> validToken(registration))
          .orElseGet(
              () ->
                  singleFetch(
                      client, registration, waitLimit, () -> authorize(client, registration)));
    } catch (StratumException unavailable) {
      // Marked here, not by its code: the same code after a 401's renewal follows a real send.
      throw new InterceptorFailure(unavailable);
    }
  }

  /**
   * Replaces the registration's token after the other service refused it, as one fetch with every
   * other fetch of the registration: the refused token is dropped only if it is still stored, so a
   * newer token stays and no fetch publishes a token while it is dropped.
   *
   * @param client the client's name, for the failure and its warning
   * @param registration the registration's name
   * @param refusedToken the token the other service refused
   * @param waitLimit the longest wait for the fetch
   * @return the registration's new token
   * @throws StratumException {@code SX-HTTPC-0002} when the new token cannot be fetched in time
   */
  public OAuth2AccessToken renew(
      String client, String registration, String refusedToken, Duration waitLimit) {
    return singleFetch(
        client,
        registration,
        waitLimit,
        () -> dropAndAuthorize(client, registration, refusedToken));
  }

  /**
   * Runs the attempt with the token given, which its request then sends, so that the token is not
   * read again, even when it expires within the skew, and a 401 refused exactly that token.
   *
   * @param registration the registration's name
   * @param token the token the attempt sends
   * @param attempt the attempt
   * @param <T> what the attempt returns
   * @return what the attempt returned
   */
  public <T> @Nullable T sending(
      String registration, OAuth2AccessToken token, Supplier<@Nullable T> attempt) {
    return ScopedValue.where(ATTEMPT_TOKEN, new AttemptToken(registration, token))
        .call(attempt::get);
  }

  /** Returns the token the current attempt was given for the registration, if any. */
  private Optional<OAuth2AccessToken> attemptToken(String registration) {
    return Optional.of(ATTEMPT_TOKEN)
        .filter(ScopedValue::isBound)
        .map(ScopedValue::get)
        .filter(given -> given.registration().equals(registration))
        .map(AttemptToken::token);
  }

  /** Runs the fetch unless one of the registration runs already, and waits for the running one. */
  private OAuth2AccessToken singleFetch(
      String client, String registration, Duration waitLimit, Supplier<OAuth2AccessToken> fetch) {
    var own = new CompletableFuture<OAuth2AccessToken>();
    var running = fetches.putIfAbsent(registration, own);
    try {
      return running == null
          ? fetch(registration, own, waitLimit, fetch)
          : waitFor(running, waitLimit, registration);
    } catch (OAuth2AuthorizationException refused) {
      throw unavailable(client, registration, refused);
    }
  }

  /** Drops the refused token if it is still stored, then asks for the registration's token. */
  private OAuth2AccessToken dropAndAuthorize(
      String client, String registration, String refusedToken) {
    dropIfStored(registration, refusedToken);
    return authorize(client, registration);
  }

  /** Removes the registration's stored token if it is still the refused one. */
  private void dropIfStored(String registration, String refusedToken) {
    if (storedToken(registration)
        .map(OAuth2AccessToken::getTokenValue)
        .filter(refusedToken::equals)
        .isPresent()) {
      store.removeAuthorizedClient(registration, SERVICE_PRINCIPAL);
    }
  }

  /**
   * Fetches the token on this thread into the caller's own future, which every other caller of the
   * registration waits on, and returns it or throws its failure.
   */
  private OAuth2AccessToken fetch(
      String registration,
      CompletableFuture<OAuth2AccessToken> own,
      Duration waitLimit,
      Supplier<OAuth2AccessToken> fetch) {
    try {
      // Runs on this thread; the future keeps the token or the failure for the waiting callers.
      own.completeAsync(fetch, Runnable::run);
    } finally {
      fetches.remove(registration, own);
    }
    return waitFor(own, waitLimit, registration);
  }

  /** Asks Spring Security for the registration's token, through the client's manager. */
  private OAuth2AccessToken authorize(String client, String registration) {
    var request =
        OAuth2AuthorizeRequest.withClientRegistrationId(registration)
            .principal(SERVICE_PRINCIPAL)
            .build();
    return Optional.ofNullable(managers.computeIfAbsent(client, newManager).authorize(request))
        .map(OAuth2AuthorizedClient::getAccessToken)
        .orElseThrow(() -> new IllegalStateException("No token for " + registration));
  }

  /** Waits for another caller's fetch, at most for the time this caller has left. */
  private OAuth2AccessToken waitFor(
      CompletableFuture<OAuth2AccessToken> running, Duration waitLimit, String registration) {
    try {
      return running.get(waitLimit.toNanos(), TimeUnit.NANOSECONDS);
    } catch (ExecutionException failed) {
      throw failed.getCause() instanceof RuntimeException cause
          ? cause
          : new IllegalStateException("Token fetch failed for " + registration, failed);
    } catch (TimeoutException late) {
      throw new OAuth2AuthorizationException(new OAuth2Error("token_wait_timeout"), late);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for a token", interrupted);
    }
  }

  /** Returns the registration's stored token when it is not about to expire. */
  private Optional<OAuth2AccessToken> validToken(String registration) {
    var soon = clock.instant().plus(EXPIRY_SKEW);
    return storedToken(registration).filter(token -> isValidAfter(token, soon));
  }

  /** Returns whether a token is still valid at the time given; one with no expiry always is. */
  private boolean isValidAfter(OAuth2AccessToken token, Instant time) {
    var expiresAt = token.getExpiresAt();
    return expiresAt == null || expiresAt.isAfter(time);
  }

  /** Returns the registration's stored token, valid or not. */
  private Optional<OAuth2AccessToken> storedToken(String registration) {
    return Optional.ofNullable(
            store.<OAuth2AuthorizedClient>loadAuthorizedClient(registration, SERVICE_PRINCIPAL))
        .map(OAuth2AuthorizedClient::getAccessToken);
  }

  /**
   * Returns {@code SX-HTTPC-0002} for a token that cannot be fetched, with no detail, as the
   * caller's answer shows it; the warning, once a minute, names the client and the registration.
   */
  private StratumException unavailable(
      String client, String registration, OAuth2AuthorizationException refused) {
    if (warnings.shouldWarn(client) && log.isWarnEnabled()) {
      // Names and the provider's error code only; a client's name holds no line break.
      log.warn(
          ("HTTP client "
                  + client
                  + " could not get a token of registration "
                  + registration
                  + " ("
                  + refused.getError().getErrorCode()
                  + ")")
              .replaceAll("[\r\n]", " "));
    }
    return new StratumException(HttpClientErrorCode.DOWNSTREAM_UNAVAILABLE, "", refused);
  }

  /**
   * The token an attempt was given, with its registration.
   *
   * @param registration the registration's name
   * @param token the token the attempt sends
   */
  private record AttemptToken(String registration, OAuth2AccessToken token) {}
}
