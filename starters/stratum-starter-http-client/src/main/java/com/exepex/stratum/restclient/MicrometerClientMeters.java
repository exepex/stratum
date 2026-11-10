package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.httpclient.HttpClientConstants;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;

/**
 * The meters of Stratum's clients in the service's Micrometer registry, each tagged with the
 * client's name and never with a path, query or header value. Each client's meters are made once,
 * on its first call.
 */
@StratumInternal
@RequiredArgsConstructor
public class MicrometerClientMeters implements ClientMeters {

  /** The breaker states the state gauge shows: the three a breaker goes through by itself. */
  private static final List<CircuitBreaker.State> BREAKER_STATES =
      List.of(
          CircuitBreaker.State.CLOSED, CircuitBreaker.State.OPEN, CircuitBreaker.State.HALF_OPEN);

  /** The tag of a breaker state gauge that names its state. */
  private static final String STATE_TAG = "state";

  /** The tag of the call timer that names the outcome. */
  private static final String OUTCOME_TAG = "outcome";

  /** The service's registry. */
  private final MeterRegistry registry;

  /** The call meters of each client, made on its first call. */
  private final Map<String, CallMeters> metersByClient = new ConcurrentHashMap<>();

  @Override
  public void callEnded(ClientCallOutcome outcome) {
    var meters = metersFor(outcome.call().client());
    meters.calls().get(outcome.outcome()).record(outcome.duration());
    if (outcome.attempts() > 1) {
      meters.retries().increment(outcome.attempts() - 1.0);
    }
  }

  @Override
  public void poolRefused(String client) {
    metersFor(client).poolRefused().increment();
  }

  @Override
  public void poolMade(String name, PoolingHttpClientConnectionManager pool) {
    new PoolingHttpClientConnectionManagerMetricsBinder(
            pool, name, HttpClientConstants.CLIENT_TAG, name)
        .bindTo(registry);
  }

  @Override
  public void breakerMade(String client, CircuitBreaker breaker) {
    BREAKER_STATES.forEach(state -> stateGauge(client, breaker, state));
  }

  /** Shows 1 while the breaker is in the state, and 0 otherwise, read at each scrape. */
  private void stateGauge(String client, CircuitBreaker breaker, CircuitBreaker.State state) {
    Gauge.builder(
            HttpClientConstants.BREAKER_STATE_METER,
            breaker,
            current -> current.getState() == state ? 1 : 0)
        .tag(HttpClientConstants.CLIENT_TAG, client)
        .tag(STATE_TAG, state.name().toLowerCase(Locale.ROOT))
        .description("1 for the current state of the client's circuit breaker, 0 for the others")
        .register(registry);
  }

  /** Returns a client's call meters, made on first use. */
  private CallMeters metersFor(String client) {
    return metersByClient.computeIfAbsent(client, this::newMeters);
  }

  /** Makes a client's call timer for each outcome, retry counter and full-pool counter. */
  private CallMeters newMeters(String client) {
    Map<CallOutcome, Timer> calls =
        Stream.of(CallOutcome.values())
            .collect(
                Collectors.toMap(
                    Function.identity(),
                    outcome -> callTimer(client, outcome),
                    (first, second) -> first,
                    () -> new EnumMap<>(CallOutcome.class)));
    return CallMeters.builder()
        .calls(calls)
        .retries(
            Counter.builder(HttpClientConstants.RETRIES_METER)
                .tag(HttpClientConstants.CLIENT_TAG, client)
                .description("Attempts sent after the first attempt of a call")
                .register(registry))
        .poolRefused(
            Counter.builder(HttpClientConstants.POOL_REFUSED_METER)
                .tag(HttpClientConstants.CLIENT_TAG, client)
                .description("Attempts refused because no connection came free within max-wait")
                .register(registry))
        .build();
  }

  /** Makes a client's timer of the calls that ended with one outcome. */
  private Timer callTimer(String client, CallOutcome outcome) {
    return Timer.builder(HttpClientConstants.CALLS_METER)
        .tag(HttpClientConstants.CLIENT_TAG, client)
        .tag(OUTCOME_TAG, outcome.name())
        .description("Whole calls of a Stratum client, retries and waits included")
        .register(registry);
  }

  /**
   * The call meters of one client.
   *
   * @param calls the timer of each outcome
   * @param retries the attempts after the first
   * @param poolRefused the attempts a full pool refused
   */
  @Builder
  private record CallMeters(Map<CallOutcome, Timer> calls, Counter retries, Counter poolRefused) {}
}
