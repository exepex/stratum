package com.exepex.stratum.restclient;

import com.exepex.stratum.httpclient.OperationSettings;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.IntervalBiFunction;
import io.github.resilience4j.retry.Retry;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * The retry, breaker, waits and timeouts of one client, made by {@link ClientResilience} on the
 * client's first call.
 *
 * @param retry the client's retry
 * @param breaker the client's breaker, or {@code null} when it has none
 * @param waits the client's wait before each next attempt, Stratum's or the service's
 * @param totalTimeout the time allowed for a whole call of a method with no operation
 * @param operations the settings of each operation, by its name, with the timeouts it leaves unset
 *     taken from the client
 */
@Builder
record ClientGuards(
    Retry retry,
    @Nullable CircuitBreaker breaker,
    IntervalBiFunction<ClientAttempt> waits,
    Duration totalTimeout,
    Map<String, OperationSettings> operations) {

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

  /**
   * Returns the time allowed for a whole call: its operation's {@code total-timeout}, else the
   * client's.
   *
   * @param operation the call's operation, or {@code null} for a call of none
   * @return the time allowed
   */
  Duration totalTimeoutOf(@Nullable String operation) {
    return settingsOf(operation).map(OperationSettings::totalTimeout).orElse(totalTimeout);
  }

  /**
   * Returns the answer timeout of a call's operation, its own {@code read-timeout} or the client's.
   *
   * @param operation the call's operation, or {@code null} for a call of none
   * @return the operation's answer timeout, or {@code null} for a call of none, which keeps the
   *     client's own
   */
  @Nullable Duration readTimeoutOf(@Nullable String operation) {
    return settingsOf(operation).map(OperationSettings::readTimeout).orElse(null);
  }

  /** Returns the settings of a call's operation, empty for a call of none. */
  private Optional<OperationSettings> settingsOf(@Nullable String operation) {
    return Optional.ofNullable(operation).map(operations::get);
  }
}
