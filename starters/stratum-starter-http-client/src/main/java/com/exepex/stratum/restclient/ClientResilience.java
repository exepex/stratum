package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.ClientCircuitBreaker;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import com.exepex.stratum.httpclient.HttpClientProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import io.github.resilience4j.core.IntervalBiFunction;
import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClientException;
import org.springframework.web.service.invoker.HttpExchangeAdapter;

/**
 * The retries and circuit breaker of each Stratum client: one Resilience4j {@link Retry} around one
 * {@link CircuitBreaker} around each attempt, made on the client's first call from its settings and
 * the service's {@link StratumClientCustomizer} beans. Every call of a client's interface runs
 * through it, and any other call can too ({@link #call}). Each call that ends writes its log line
 * and is counted in the meters once; each change of a breaker's state is logged once.
 */
@Slf4j
@StratumInternal
@RequiredArgsConstructor
public class ClientResilience implements ClientExchangeDecorator {

  /** The settings of every client. */
  private final HttpClientProperties properties;

  /** The service's changes to the clients' retry and breaker rules. */
  private final ObjectProvider<StratumClientCustomizer> customizers;

  /** The clock a {@code Retry-After} date is read against. */
  private final Clock clock;

  /** The resend after a 401 of a client with a service token, present with the OAuth2 client. */
  private final ObjectProvider<ServiceTokenResend> tokenResend;

  /** The log line of each call that ends. */
  private final ClientCallLog callLog;

  /** The meters of the clients, present with Micrometer and a registry. */
  private final ObjectProvider<ClientMeters> meters;

  /** The retry and breaker of each client, made on its first call. */
  private final Map<String, ClientGuards> guardsByClient = new ConcurrentHashMap<>();

  /**
   * Returns the client's calls with its retries and breaker around each of them.
   *
   * @param client the client's name
   * @param exchange the client's calls
   * @return the calls with retries and the breaker
   */
  @Override
  public HttpExchangeAdapter decorate(String client, HttpExchangeAdapter exchange) {
    return new ResilientExchangeAdapter(exchange, client, this);
  }

  /**
   * Returns the order of the retries and breaker, inside the error translation and outside the
   * resend after a 401.
   *
   * @return the order
   */
  @Override
  public int getOrder() {
    return HttpClientConstants.RESILIENCE_ORDER;
  }

  /**
   * Returns a client's retry, made on first use.
   *
   * @param client the client's name
   * @return the retry
   */
  public Retry retry(String client) {
    return guards(client).retry();
  }

  /**
   * Returns a client's circuit breaker, made on first use.
   *
   * @param client the client's name
   * @return the breaker, or empty when the client's {@code circuit-breaker.enabled} is false
   */
  public Optional<CircuitBreaker> circuitBreaker(String client) {
    return Optional.ofNullable(guards(client).breaker());
  }

  /**
   * Runs one call of a client under its retries and breaker, each attempt within the time left
   * before the client's {@code total-timeout}. When no attempt succeeds, the last one's failure is
   * thrown. When the call ends, its log line is written and it is counted in the meters.
   *
   * @param call the call
   * @param exchange one attempt of the call
   * @param <T> what the call returns
   * @return what the last attempt returned
   * @throws RestClientException the last attempt's failure, such as an error answer
   * @throws StratumException {@code SX-HTTPC-0003} when the client's breaker is open, so the call
   *     was not sent, or the failure that stopped an attempt before it was sent, such as {@code
   *     SX-HTTPC-0002} when the service token cannot be fetched
   * @throws RuntimeException an interceptor's own failure, as it threw it: the call was not sent,
   *     so it is neither retried nor counted by the breaker
   */
  public <T> @Nullable T call(ClientCall call, Supplier<@Nullable T> exchange) {
    try {
      return callMarkingInterceptorFailures(call, exchange);
    } catch (InterceptorFailure failure) {
      failure.throwOriginal();
      throw failure;
    }
  }

  /**
   * Runs one call as {@link #call} does, but passes an interceptor's failure on as an {@link
   * InterceptorFailure}, for an outer layer that unwraps it after its own rules, such as the error
   * translation of a client's interface.
   *
   * @param call the call
   * @param exchange one attempt of the call
   * @param <T> what the call returns
   * @return what the last attempt returned
   */
  <T> @Nullable T callMarkingInterceptorFailures(ClientCall call, Supplier<@Nullable T> exchange) {
    var startNanos = System.nanoTime();
    var lastResult = new AtomicReference<@Nullable T>();
    var lastSent = new AtomicReference<@Nullable ClientAttempt>();
    var sentAttempts = new AtomicInteger();
    @Nullable CallOutcome notSent = null;
    var attemptsEnded = false;
    ClientAttempt lastAttempt;
    try {
      lastAttempt =
          attempts(
              call,
              exchange,
              lastResult,
              attempt -> noteSent(call, attempt, lastSent, sentAttempts));
      attemptsEnded = true;
    } catch (StratumException failure) {
      notSent = RetryRules.notSentOutcome(failure);
      throw failure;
    } finally {
      var duration = Duration.ofNanos(System.nanoTime() - startNanos);
      if (attemptsEnded) {
        report(ClientCallOutcome.of(call, lastSent.get(), null, sentAttempts.get(), duration));
      } else {
        // Any other exception that escapes the attempts, such as a bug, ends the call as an error.
        var endedAs = Objects.requireNonNullElse(notSent, CallOutcome.ERROR);
        report(ClientCallOutcome.of(call, lastSent.get(), endedAs, sentAttempts.get(), duration));
      }
    }
    lastAttempt.getFailure().ifPresent(this::rethrow);
    return lastResult.get();
  }

  /** Runs the attempts of a call under the client's retry and returns the last one. */
  private <T> ClientAttempt attempts(
      ClientCall call,
      Supplier<@Nullable T> exchange,
      AtomicReference<@Nullable T> lastResult,
      UnaryOperator<ClientAttempt> noteEnded) {
    var guards = guards(call.client());
    var deadlineNanos = System.nanoTime() + guards.totalTimeout().toNanos();
    var attemptNumber = new AtomicInteger();
    var template =
        ClientAttempt.builder()
            .method(call.method())
            .streamedBody(call.streamedBody())
            .timeLeft(Duration.ZERO)
            .nextWait(Duration.ZERO)
            .build();
    return guards
        .retry()
        .executeSupplier(
            () ->
                noteEnded.apply(
                    attempt(
                        guards,
                        template,
                        attemptNumber.incrementAndGet(),
                        deadlineNanos,
                        call.streamedDownload(),
                        () ->
                            remember(
                                lastResult,
                                guards.protect(
                                    withResend(call.client(), call.streamedBody(), exchange))))));
  }

  /**
   * Keeps an attempt the breaker did not refuse as the call's last sent one, counts it, and counts
   * a full pool's refusal; returns the attempt.
   */
  private ClientAttempt noteSent(
      ClientCall call,
      ClientAttempt attempt,
      AtomicReference<@Nullable ClientAttempt> lastSent,
      AtomicInteger sentAttempts) {
    var failure = attempt.getFailure();
    if (failure.filter(CallNotPermittedException.class::isInstance).isEmpty()) {
      lastSent.set(attempt);
      sentAttempts.incrementAndGet();
    }
    if (failure.filter(RetryRules::isPoolFull).isPresent()) {
      meters.ifAvailable(clientMeters -> clientMeters.poolRefused(call.client()));
    }
    return attempt;
  }

  /** Writes a call's log line and counts it in the meters. */
  private void report(ClientCallOutcome outcome) {
    callLog.write(outcome);
    meters.ifAvailable(clientMeters -> clientMeters.callEnded(outcome));
  }

  /**
   * Runs one attempt within the call's deadline and returns how it ended, never throwing a failure
   * of the call, so that the retry rules decide on every attempt in one way.
   */
  private ClientAttempt attempt(
      ClientGuards guards,
      ClientAttempt template,
      int attemptNumber,
      long deadlineNanos,
      boolean streamedDownload,
      Supplier<@Nullable Object> exchange) {
    @Nullable Object result = null;
    @Nullable RuntimeException failure = null;
    var sentTo = new AtomicReference<@Nullable URI>();
    try {
      result = AttemptTimeLeft.runWithin(deadlineNanos, streamedDownload, sentTo, exchange);
    } catch (RestClientException | CallNotPermittedException attemptFailure) {
      failure = attemptFailure;
    }
    var ended =
        template.toBuilder()
            .result(result)
            .failure(failure)
            .url(sentTo.get())
            .timeLeft(Duration.ofNanos(Math.max(0, deadlineNanos - System.nanoTime())))
            .build();
    return ended.toBuilder()
        .nextWait(Duration.ofMillis(guards.waits().apply(attemptNumber, Either.right(ended))))
        .build();
  }

  /**
   * Returns one attempt with the resend after a 401 inside it, for a client with a service token;
   * the resend and its new token then count as one attempt.
   */
  private <T> Supplier<@Nullable T> withResend(
      String client, boolean streamedBody, Supplier<@Nullable T> exchange) {
    var resend = tokenResend.getIfAvailable();
    return resend == null ? exchange : () -> resend.call(client, streamedBody, exchange);
  }

  /**
   * Throws the last attempt's failure as the call's own: one of the two that {@link #attempt}
   * catches, a refusal of the open breaker as {@code SX-HTTPC-0003}.
   */
  private void rethrow(RuntimeException failure) {
    switch (failure) {
      case CallNotPermittedException refused ->
          throw new StratumException(HttpClientErrorCode.BREAKER_OPEN, "", refused);
      case RestClientException failed -> throw failed;
      default -> throw new IllegalStateException("An attempt failed in an unexpected way", failure);
    }
  }

  /** Returns a client's retry, breaker and back-off, made on first use. */
  private ClientGuards guards(String client) {
    return guardsByClient.computeIfAbsent(client, this::newGuards);
  }

  /** Keeps the result of the latest attempt, typed for the caller, and returns it. */
  private <T> @Nullable Object remember(
      AtomicReference<@Nullable T> lastResult, @Nullable T value) {
    lastResult.set(value);
    return value;
  }

  /** Makes a client's retry, breaker and back-off from its settings and the customizers. */
  private ClientGuards newGuards(String client) {
    var settings = properties.clientSettings(client);
    var retrySettings = Objects.requireNonNull(settings.retry());
    var backOff = RetryRules.backOff(retrySettings);
    var rules =
        RetryConfig.<ClientAttempt>custom()
            .maxAttempts(Objects.requireNonNull(retrySettings.maxRetries()) + 1)
            .retryOnResult(RetryRules::shouldRetry)
            .retryOnException(failure -> false)
            .intervalBiFunction(
                (attempts, outcome) ->
                    RetryRules.stratumWaitMillis(attempts, outcome, backOff, clock));
    customizers.orderedStream().forEach(customizer -> customizer.customizeRetry(client, rules));
    var chosenRules = rules.build();
    // The wait is worked out once per attempt, so that the time-left check and the sleep agree,
    // with the service's wait when its customizer set one.
    IntervalBiFunction<ClientAttempt> waits = chosenRules.getIntervalBiFunction();
    var retryConfig =
        RetryConfig.<ClientAttempt>from(chosenRules)
            .intervalBiFunction(
                (attempts, outcome) -> RetryRules.carriedWaitMillis(attempts, outcome, waits))
            .build();
    return ClientGuards.builder()
        .retry(Retry.of(client, retryConfig))
        .breaker(newBreaker(client, Objects.requireNonNull(settings.circuitBreaker())))
        .waits(waits)
        .totalTimeout(Objects.requireNonNull(settings.totalTimeout()))
        .build();
  }

  /**
   * Makes a client's breaker from its settings and the customizers, or none when {@code
   * circuit-breaker.enabled} is false.
   */
  private @Nullable CircuitBreaker newBreaker(String client, ClientCircuitBreaker settings) {
    if (!Boolean.TRUE.equals(settings.enabled())) {
      return null;
    }
    var breakerConfig = RetryRules.breakerConfig(settings);
    customizers
        .orderedStream()
        .forEach(customizer -> customizer.customizeCircuitBreaker(client, breakerConfig));
    var breaker = CircuitBreaker.of(client, breakerConfig.build());
    breaker.getEventPublisher().onStateTransition(this::logStateChange);
    meters.ifAvailable(clientMeters -> clientMeters.breakerMade(client, breaker));
    return breaker;
  }

  /** Logs a change of a breaker's state once, such as from closed to open. */
  private void logStateChange(CircuitBreakerOnStateTransitionEvent event) {
    if (!log.isWarnEnabled()) {
      return;
    }
    var line =
        "The circuit breaker of HTTP client "
            + event.getCircuitBreakerName()
            + " went from "
            + event.getStateTransition().getFromState()
            + " to "
            + event.getStateTransition().getToState();
    // A client's name is checked at startup and holds no line break; SpotBugs only sees this.
    log.warn(line.replaceAll("[\r\n]", " "));
  }

  /**
   * The retry, breaker, waits and total time of one client.
   *
   * @param retry the client's retry
   * @param breaker the client's breaker, or {@code null} when it has none
   * @param waits the client's wait before each next attempt, Stratum's or the service's
   * @param totalTimeout the time allowed for a whole call
   */
  @Builder
  private record ClientGuards(
      Retry retry,
      @Nullable CircuitBreaker breaker,
      IntervalBiFunction<ClientAttempt> waits,
      Duration totalTimeout) {

    /**
     * Runs one attempt through the breaker, when the client has one.
     *
     * @param exchange the attempt
     * @param <T> what the attempt returns
     * @return what the attempt returned
     */
    <T> @Nullable T protect(Supplier<@Nullable T> exchange) {
      return breaker == null ? exchange.get() : breaker.executeSupplier(exchange);
    }
  }
}
