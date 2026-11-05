package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.ErrorReport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.cache.RedisCache;

/**
 * Decides whether Redis caches may call Redis. After {@code stratum.cache.redis-failure-threshold}
 * calls in a row could not reach Redis or timed out, every Redis cache of the application skips
 * Redis for {@code stratum.cache.redis-retry-interval}; a call that reaches Redis starts the count
 * again. Then one call tries again while the others keep skipping: a success puts Redis back in use
 * for all calls, and a failure skips Redis again at once. Until the first call has reached Redis,
 * only that call tries, so the others never queue behind the first connect. Each time the calls
 * start skipping is warned with {@code SX-CACHE-1006}, at most once per {@code
 * stratum.cache.warning-interval}, and counted when Redis could not be reached.
 */
@StratumInternal
@Slf4j
@RequiredArgsConstructor
public class RedisRetryGate {

  /** The state before the first call: its time has passed, so the first call tries Redis. */
  private static final GateState STARTING =
      GateState.builder().phase(Phase.SKIPPING).until(Instant.MIN).build();

  /** The state while every call uses Redis. */
  private static final GateState USING =
      GateState.builder().phase(Phase.USING_REDIS).until(Instant.MIN).build();

  /** How long Redis is skipped after a failure, and how long one trial may take. */
  private final Duration retryInterval;

  /** How many failures in a row, while every call uses Redis, make the calls skip Redis. */
  private final int failureThreshold;

  /** Tells the current time; a test sets it. */
  private final Clock clock;

  /** Admits one warning per warning interval, on its own slot. */
  private final WarningThrottle throttle;

  /** Counts the times Redis could not be reached. */
  private final CacheCounters counters;

  /**
   * The caches whose own command timeout is below {@code spring.data.redis.timeout}: their timeout
   * says nothing about Redis as a whole, so it never makes the other caches skip Redis.
   */
  private final Set<String> shortTimeoutCaches;

  /** The current state; replaced only on a change, so a call using Redis costs one read. */
  private final AtomicReference<GateState> state = new AtomicReference<>(STARTING);

  /**
   * Returns the cache with the gate in front of it when it is a Redis cache, else the cache itself,
   * so other caches never skip because Redis is down.
   *
   * @param cache the cache the cache manager made
   * @return the gated cache, or the cache unchanged
   */
  public Cache gated(Cache cache) {
    return cache instanceof RedisCache
        ? new RedisGatedCache(cache, this, !shortTimeoutCaches.contains(cache.getName()))
        : cache;
  }

  /**
   * Returns whether a call may use Redis now: always while Redis is in use; otherwise only the one
   * call that takes the next trial once the wait is over.
   *
   * @return whether to call Redis
   */
  boolean admits() {
    var current = state.get();
    if (current.phase() == Phase.USING_REDIS) {
      return true;
    }
    var now = clock.instant();
    return !now.isBefore(current.until())
        && state.compareAndSet(current, current.trying(now.plus(retryInterval)));
  }

  /**
   * Returns whether every call may use Redis; a call that cannot prove Redis is back, such as a
   * write sent in the background, uses Redis only then.
   *
   * @return whether Redis is in use
   */
  boolean usesRedis() {
    return state.get().phase() == Phase.USING_REDIS;
  }

  /**
   * Returns whether Redis caches use Redis, for the gauge: false once the calls skip Redis, until a
   * trial succeeds.
   *
   * @return whether Redis is available
   */
  public boolean isAvailable() {
    return !state.get().down();
  }

  /**
   * Records a call that reached Redis: a trial in progress puts Redis back in use, and failures
   * counted while every call uses Redis start again from 0.
   */
  void reached() {
    var current = state.get();
    if (current.phase() == Phase.TRYING || current.failures() > 0) {
      state.compareAndSet(current, USING);
    }
  }

  /**
   * Records a call that ended with an error that is not Redis's own, such as a loader's. It says
   * nothing about Redis now, since the call may have started before an outage, so a trial in
   * progress is not taken as success: it ends, and the next call tries again at once.
   */
  void released() {
    var current = state.get();
    if (current.phase() == Phase.TRYING) {
      state.compareAndSet(
          current, current.toBuilder().phase(Phase.SKIPPING).until(clock.instant()).build());
    }
  }

  /**
   * Records a failed Redis call. A connect failure, a command refused because the connection is
   * down, or a timed-out command counts while every call uses Redis, and the one that reaches the
   * threshold makes every call skip Redis; a failed trial does so at once. While the calls already
   * skip Redis, a late failure neither extends the wait nor counts twice. A timeout of a cache
   * whose own timeout is below the global one releases a trial instead, as it says nothing about
   * Redis as a whole. Any other failure means Redis answered.
   *
   * @param failure the failure, as Spring Data translated it
   * @param timeoutOpensGate whether a timeout of the failed call's cache makes every call skip
   *     Redis
   */
  void failed(RuntimeException failure, boolean timeoutOpensGate) {
    var unreachable = isUnreachable(failure);
    var timedOut = failure instanceof QueryTimeoutException;
    if (timedOut && !timeoutOpensGate) {
      released();
      return;
    }
    if (!unreachable && !timedOut) {
      reached();
      return;
    }
    var skipping =
        GateState.builder()
            .phase(Phase.SKIPPING)
            .until(clock.instant().plus(retryInterval))
            .down(true)
            .build();
    var before = state.getAndUpdate(current -> afterFailure(current, skipping));
    if (before.phase() == Phase.SKIPPING
        || afterFailure(before, skipping).phase() != Phase.SKIPPING) {
      return;
    }
    if (unreachable) {
      counters.redisConnectFailure();
    }
    if (throttle.shouldWarn(CacheConstants.REDIS_WARNING_SLOT) && log.isWarnEnabled()) {
      var report = ErrorReport.of(CacheErrorCode.REDIS_UNREACHABLE, "next try in " + retryInterval);
      // The report already turns line breaks into spaces; SpotBugs only sees this replacement.
      log.warn(report.getCodeAndMessage().replaceAll("[\r\n]", " "));
    }
  }

  /**
   * Returns the state after one more failure that can make the calls skip Redis: the count grows
   * while every call uses Redis and is below the threshold; otherwise the calls skip Redis, unless
   * they already do. It has no side effects, as the state's update may run it more than once.
   */
  private GateState afterFailure(GateState current, GateState skipping) {
    if (current.phase() == Phase.SKIPPING) {
      return current;
    }
    return current.phase() == Phase.USING_REDIS && current.failures() + 1 < failureThreshold
        ? current.toBuilder().failures(current.failures() + 1).build()
        : skipping;
  }

  /**
   * Returns whether the failure says Redis cannot be reached: a failed connect, or one of Lettuce's
   * two connection-loss errors, which it reports only as a plain {@code RedisException} with text.
   */
  private boolean isUnreachable(RuntimeException failure) {
    return failure instanceof RedisConnectionFailureException
        || failure instanceof RedisSystemException && isConnectionLoss(failure.getCause());
  }

  /** Returns whether Lettuce's error says the connection is down or dropped mid-command. */
  private boolean isConnectionLoss(@Nullable Throwable cause) {
    var message = cause == null ? null : cause.getMessage();
    return message != null
        && (message.startsWith(CacheConstants.LETTUCE_REJECTED)
            || message.startsWith(CacheConstants.LETTUCE_DISCONNECTED));
  }

  /** Where the gate is: every call uses Redis, all skip, or one call is trying. */
  enum Phase {

    /** Every call uses Redis. */
    USING_REDIS,

    /** Every call skips Redis until the state's time. */
    SKIPPING,

    /** One call is trying Redis; the others skip until it succeeds or its time passes. */
    TRYING
  }

  /**
   * One state of the gate.
   *
   * @param phase where the gate is
   * @param until when skipping ends, or when a lost trial may be replaced
   * @param down whether the calls skipped Redis since it was last reached
   * @param failures the failures in a row while every call uses Redis
   */
  @Builder(toBuilder = true)
  record GateState(Phase phase, Instant until, boolean down, int failures) {

    /**
     * Returns this state as a trial, keeping whether a failure was seen.
     *
     * @param replaceableAt when another call may take over the trial
     * @return the trying state
     */
    GateState trying(Instant replaceableAt) {
      return toBuilder().phase(Phase.TRYING).until(replaceableAt).build();
    }
  }
}
