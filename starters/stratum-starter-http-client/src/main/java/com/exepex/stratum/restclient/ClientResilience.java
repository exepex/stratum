package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.httpclient.ClientSettings;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClientException;
import org.springframework.web.service.invoker.HttpExchangeAdapter;

/**
 * The retries and circuit breaker of each Stratum client: one Resilience4j {@link Retry} around one
 * {@link CircuitBreaker} around each attempt, made on the client's first call from its settings and
 * the service's {@link StratumClientCustomizer} beans. Every call of a client's interface runs
 * through it, and any other call can too ({@link #call}). Each change of a breaker's state is
 * logged once.
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
   * Returns the order of the retries and breaker, the outermost of Stratum's decorators.
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
   * @return the breaker, or empty when the client's {@code circuit-breaker} is off
   */
  public Optional<CircuitBreaker> circuitBreaker(String client) {
    return Optional.ofNullable(guards(client).breaker());
  }

  /**
   * Runs one call of a client under its retries and breaker, each attempt within the time left
   * before the client's {@code total-timeout}. When no attempt succeeds, the last one's failure is
   * thrown.
   *
   * @param client the client's name
   * @param method the call's HTTP method
   * @param streamedBody whether the request's body is streamed, which is never sent twice
   * @param streamedDownload whether the answer is streamed, which keeps its full answer timeout
   * @param exchange one attempt of the call
   * @param <T> what the call returns
   * @return what the last attempt returned
   * @throws RestClientException the last attempt's failure, such as an error answer
   * @throws CallNotPermittedException when the client's breaker is open
   */
  public <T> @Nullable T call(
      String client,
      HttpMethod method,
      boolean streamedBody,
      boolean streamedDownload,
      Supplier<@Nullable T> exchange) {
    var guards = guards(client);
    var deadlineNanos = System.nanoTime() + guards.totalTimeout().toNanos();
    var attemptNumber = new AtomicInteger();
    var lastResult = new AtomicReference<@Nullable T>();
    var template =
        ClientAttempt.builder()
            .method(method)
            .streamedBody(streamedBody)
            .timeLeft(Duration.ZERO)
            .nextWait(Duration.ZERO)
            .build();
    var lastAttempt =
        guards
            .retry()
            .executeSupplier(
                () ->
                    attempt(
                        guards,
                        template,
                        attemptNumber.incrementAndGet(),
                        deadlineNanos,
                        streamedDownload,
                        () -> remember(lastResult, guards.protect(exchange))));
    lastAttempt.failure().ifPresent(this::rethrow);
    return lastResult.get();
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
    try {
      result = AttemptTimeLeft.runWithin(deadlineNanos, streamedDownload, exchange);
    } catch (RestClientException | CallNotPermittedException attemptFailure) {
      failure = attemptFailure;
    }
    return template.toBuilder()
        .result(result)
        .failure(Optional.ofNullable(failure))
        .timeLeft(Duration.ofNanos(Math.max(0, deadlineNanos - System.nanoTime())))
        .nextWait(RetryRules.nextWait(failure, attemptNumber, guards.backOff(), clock.instant()))
        .build();
  }

  /**
   * Throws the last attempt's failure as the call's own: one of the two that {@link #attempt}
   * catches.
   */
  private void rethrow(RuntimeException failure) {
    switch (failure) {
      case CallNotPermittedException refused -> throw refused;
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
    var backOff = RetryRules.backOff(Objects.requireNonNull(settings.retryDelay()));
    var retryConfig =
        RetryConfig.<ClientAttempt>custom()
            .maxAttempts(Objects.requireNonNull(settings.retries()) + 1)
            .retryOnResult(RetryRules::shouldRetry)
            .retryOnException(failure -> false)
            .intervalBiFunction((attempts, outcome) -> waitMillis(attempts, outcome, backOff));
    customizers
        .orderedStream()
        .forEach(customizer -> customizer.customizeRetry(client, retryConfig));
    return ClientGuards.builder()
        .retry(Retry.of(client, retryConfig.build()))
        .breaker(newBreaker(client, settings))
        .backOff(backOff)
        .totalTimeout(Objects.requireNonNull(settings.totalTimeout()))
        .build();
  }

  /** Makes a client's breaker, or none when {@code circuit-breaker} is false. */
  private @Nullable CircuitBreaker newBreaker(String client, ClientSettings settings) {
    if (!Boolean.TRUE.equals(settings.circuitBreaker())) {
      return null;
    }
    var breakerConfig =
        CircuitBreakerConfig.custom().recordException(RetryRules::countsAsBreakerFailure);
    customizers
        .orderedStream()
        .forEach(customizer -> customizer.customizeCircuitBreaker(client, breakerConfig));
    var breaker = CircuitBreaker.of(client, breakerConfig.build());
    breaker.getEventPublisher().onStateTransition(this::logStateChange);
    return breaker;
  }

  /**
   * Returns the wait before the next attempt: the one the last attempt carries, or the back-off's
   * when a service's own rules retry after an exception.
   */
  private long waitMillis(
      int attempts, Either<Throwable, ClientAttempt> outcome, IntervalFunction backOff) {
    return outcome.isRight() ? outcome.get().nextWait().toMillis() : backOff.apply(attempts);
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
   * The retry, breaker, back-off and total time of one client.
   *
   * @param retry the client's retry
   * @param breaker the client's breaker, or {@code null} when it has none
   * @param backOff the client's back-off
   * @param totalTimeout the time allowed for a whole call
   */
  @Builder
  private record ClientGuards(
      Retry retry,
      @Nullable CircuitBreaker breaker,
      IntervalFunction backOff,
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
