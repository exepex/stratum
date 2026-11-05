package com.exepex.stratum.cache.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.cache.RedisRetryGate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

/**
 * The steps the integration tests take around a paused Redis container: wait until the caches use
 * Redis again, empty it, pause it, time a cached call and read the failure counters.
 */
@UtilityClass
public class PausedRedisSteps {

  /** How long to wait for the caches to use Redis again after a pause. */
  private static final Duration RECOVERY_WAIT = Duration.ofSeconds(30);

  /**
   * Waits until the caches use Redis again after an earlier test's pause, then empties Redis, so a
   * test's calls all miss.
   *
   * @param catalog the cached service, called once per check
   * @param gate the gate in front of Redis caches
   * @param redis the service's Redis access
   */
  public static void startFromEmptyRedis(
      ProductCatalog catalog, RedisRetryGate gate, StringRedisTemplate redis) {
    await().atMost(RECOVERY_WAIT).until(() -> redisInUseAfterOneCall(catalog, gate));
    redis.execute(PausedRedisSteps::emptyRedis, true);
  }

  /**
   * Makes one cached call and returns whether Redis caches use Redis.
   *
   * @param catalog the cached service
   * @param gate the gate in front of Redis caches
   * @return whether Redis caches use Redis after the call
   */
  public static boolean redisInUseAfterOneCall(ProductCatalog catalog, RedisRetryGate gate) {
    catalog.priceOf("warm-up");
    return gate.isAvailable();
  }

  /**
   * Makes one call, checks it returns the method's real value and returns how long it took.
   *
   * @param call the cached call
   * @param expectedValue the method's real value
   * @return how long the call took
   */
  public static Duration durationOf(Supplier<String> call, String expectedValue) {
    var start = System.nanoTime();
    assertThat(call.get()).isEqualTo(expectedValue);
    return Duration.ofNanos(System.nanoTime() - start);
  }

  /**
   * Returns the counter of one operation's failures on one cache.
   *
   * @param meterRegistry the service's registry
   * @param cache the cache's name
   * @param operation the operation, such as {@value CacheConstants#OPERATION_GET}
   * @return the counter, or {@code null} when no failure was counted yet
   */
  public static @Nullable Counter failureCounter(
      MeterRegistry meterRegistry, String cache, String operation) {
    return meterRegistry
        .find(CacheConstants.METRIC_FAILURES)
        .tags(CacheConstants.TAG_CACHE, cache, CacheConstants.TAG_OPERATION, operation)
        .counter();
  }

  /**
   * Returns the counted failures of one operation on one cache.
   *
   * @param meterRegistry the service's registry
   * @param cache the cache's name
   * @param operation the operation, such as {@value CacheConstants#OPERATION_GET}
   * @return the count, zero when none were counted yet
   */
  public static double failuresSoFar(MeterRegistry meterRegistry, String cache, String operation) {
    var counter = failureCounter(meterRegistry, cache, operation);
    return counter == null ? 0 : counter.count();
  }

  /**
   * Pauses Redis, so every command of the paused kind times out instead of answering. The pause
   * lifts itself after the given time even when a test never unpauses.
   *
   * @param redisContainer the Redis container
   * @param millis how long the pause lasts
   * @param mode {@code WRITE} to pause only writes; nothing to pause every command
   * @throws IOException if the command cannot be sent to the container
   * @throws InterruptedException if the wait for the command is interrupted
   */
  public static void pauseRedis(GenericContainer<?> redisContainer, long millis, String... mode)
      throws IOException, InterruptedException {
    var command = new ArrayList<>(List.of("redis-cli", "CLIENT", "PAUSE", String.valueOf(millis)));
    command.addAll(List.of(mode));
    redisContainer.execInContainer(command.toArray(String[]::new));
  }

  /** Empties the Redis database; returns a value because the callback must. */
  private static Boolean emptyRedis(RedisConnection connection) {
    connection.serverCommands().flushDb();
    return true;
  }
}
