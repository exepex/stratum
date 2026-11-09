package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.httpclient.ClientAuth;
import com.exepex.stratum.httpclient.HttpClientProperties;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Sends an attempt of a client with {@code auth.client-credentials} once more after a 401: the
 * other service refused the token, as after the provider rotated its keys, so the token the attempt
 * sent is replaced and the attempt runs again with a new one, a POST too. A second 401 is thrown
 * and warned once per client per minute. An attempt whose body is streamed is never sent twice. The
 * token is fetched before the attempt creates its request, whose timeouts are then set from the
 * time the fetch left. It runs inside each attempt of {@link ClientResilience#call}, so interface
 * calls and any other call through it both get it.
 */
@Slf4j
@StratumInternal
@RequiredArgsConstructor
public class ServiceTokenResend {

  /** The settings of every client, read for its registration. */
  private final HttpClientProperties properties;

  /** The tokens of the registrations. */
  private final SingleFetchTokenManager tokens;

  /** Limits the warning of a second 401 to one per client per minute. */
  private final ClientWarnings warnings;

  /**
   * Runs one attempt, and once more with a new token if the other service answers 401.
   *
   * @param client the client's name
   * @param streamedBody whether the request's body is streamed, which is never sent twice
   * @param attempt the attempt
   * @param <T> what the attempt returns
   * @return what the last run returned
   * @throws HttpClientErrorException.Unauthorized when the new token is refused too
   */
  public <T> @Nullable T call(String client, boolean streamedBody, Supplier<@Nullable T> attempt) {
    var registration =
        Optional.ofNullable(properties.clientSettings(client).auth())
            .map(ClientAuth::clientCredentials);
    if (registration.isEmpty()) {
      return attempt.get();
    }
    var token = tokens.token(client, registration.get(), waitLimit(client));
    if (streamedBody) {
      return tokens.sending(registration.get(), token, attempt);
    }
    try {
      return tokens.sending(registration.get(), token, attempt);
    } catch (HttpClientErrorException.Unauthorized _) {
      var renewed =
          tokens.renew(client, registration.get(), token.getTokenValue(), waitLimit(client));
      return resend(client, () -> tokens.sending(registration.get(), renewed, attempt));
    }
  }

  /** Returns how long a token fetch may take: the call's time left, else the client's timeout. */
  private Duration waitLimit(String client) {
    return AttemptTimeLeft.timeLeft()
        .orElse(Objects.requireNonNull(properties.clientSettings(client).timeout()));
  }

  /** Runs the attempt again, and warns once a minute when the new token is refused too. */
  private <T> @Nullable T resend(String client, Supplier<@Nullable T> attempt) {
    try {
      return attempt.get();
    } catch (HttpClientErrorException.Unauthorized refusedAgain) {
      if (warnings.shouldWarn(client) && log.isWarnEnabled()) {
        // A client's name is checked at startup and holds no line break.
        log.warn(
            ("HTTP client " + client + " was refused a new service token too")
                .replaceAll("[\r\n]", " "));
      }
      throw refusedAgain;
    }
  }
}
