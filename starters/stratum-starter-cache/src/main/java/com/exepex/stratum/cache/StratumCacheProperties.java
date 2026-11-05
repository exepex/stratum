package com.exepex.stratum.cache;

import com.exepex.stratum.core.properties.StratumCustomizers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import lombok.Builder;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.env.Environment;
import org.springframework.util.unit.DataSize;

/**
 * The settings of the cache module, bound to {@code stratum.cache.*} and then changed by the
 * service's {@link StratumCachePropertiesCustomizer} classes, which win. The descriptions of the
 * components are what IDEs show, so they are plain sentences.
 *
 * @param maxCaches The most caches the module admits. A name beyond it runs uncached through a
 *     shared no-op cache, so no cache is created for it. From 1. Defaults to 100.
 * @param warningInterval How often a cache's warning, such as a failed operation while Redis is
 *     down, is repeated at most. From 1s to 1h. Defaults to 1m.
 * @param maxKeySize The longest Redis cache key stored as it is, prefix included. A longer key is
 *     stored under its SHA-256 hash, so it still hits. Above 0. Defaults to 1KB.
 * @param maxValueSize The largest value stored in a Redis cache. A larger value is not stored: the
 *     method's result is returned as usual. Above 0. Defaults to 1MB.
 * @param redisRetryInterval How long every Redis cache skips Redis after Redis could not be reached
 *     or a command timed out; then one call tries again. From 1s to 5m. Defaults to 5s.
 * @param serialization How Redis cache values are stored and which types are read back.
 * @param caches Settings for one cache, by its name, which win over the settings for every cache.
 */
@Builder(toBuilder = true)
@ConfigurationProperties(prefix = CacheConstants.PREFIX)
public record StratumCacheProperties(
    @DefaultValue("100") @Min(1) int maxCaches,
    @DefaultValue("1m") Duration warningInterval,
    @DefaultValue("1KB") DataSize maxKeySize,
    @DefaultValue("1MB") DataSize maxValueSize,
    @DefaultValue("5s") Duration redisRetryInterval,
    @DefaultValue @Valid Serialization serialization,
    Map<String, CacheSettings> caches) {

  /** The shortest allowed {@code warning-interval}. */
  private static final Duration MIN_WARNING_INTERVAL = Duration.ofSeconds(1);

  /** The longest allowed {@code warning-interval}. */
  private static final Duration MAX_WARNING_INTERVAL = Duration.ofHours(1);

  /** The shortest allowed {@code redis-retry-interval}. */
  private static final Duration MIN_REDIS_RETRY_INTERVAL = Duration.ofSeconds(1);

  /** The longest allowed {@code redis-retry-interval}. */
  private static final Duration MAX_REDIS_RETRY_INTERVAL = Duration.ofMinutes(5);

  /** Keeps the settings for single caches a fixed map, empty when none are set. */
  public StratumCacheProperties {
    serialization = serialization == null ? new Serialization(List.of()) : serialization;
    caches = caches == null ? Map.of() : Map.copyOf(caches);
  }

  /**
   * Returns the settings the module uses: {@code stratum.cache.*} with the defaults for anything
   * unset, changed by every {@link StratumCachePropertiesCustomizer} in {@code
   * META-INF/spring.factories}, then validated. The module makes its own validator, as every
   * Stratum module does.
   *
   * @param environment the application's environment
   * @return the settings
   * @throws com.exepex.stratum.core.error.StratumException {@code SX-CORE-1003} if a setting breaks
   *     its rules
   */
  public static StratumCacheProperties resolve(Environment environment) {
    return StratumCustomizers.resolveSettings(
        environment,
        CacheConstants.PREFIX,
        StratumCacheProperties.class,
        StratumCachePropertiesCustomizer.class);
  }

  /**
   * Returns the longest key, in bytes, that a cache stores as it is: its own {@code max-key-size},
   * or the one for every cache.
   *
   * @param cacheName the cache's name
   * @return the limit in bytes
   */
  public long maxKeyBytesFor(String cacheName) {
    return sizeFor(cacheName, CacheSettings::maxKeySize, maxKeySize);
  }

  /**
   * Returns the largest value, in bytes, that a cache stores: its own {@code max-value-size}, or
   * the one for every cache.
   *
   * @param cacheName the cache's name
   * @return the limit in bytes
   */
  public long maxValueBytesFor(String cacheName) {
    return sizeFor(cacheName, CacheSettings::maxValueSize, maxValueSize);
  }

  /**
   * Returns whether {@code warning-interval} is from 1s to 1h. Validation reports a value out of
   * that range as {@code stratum.cache.warning-interval-in-range breaks @AssertTrue}.
   *
   * @return whether the interval is in range
   */
  @AssertTrue
  public boolean isWarningIntervalInRange() {
    return warningInterval.compareTo(MIN_WARNING_INTERVAL) >= 0
        && warningInterval.compareTo(MAX_WARNING_INTERVAL) <= 0;
  }

  /**
   * Returns whether every size limit, for every cache and for single caches, is above 0. Validation
   * reports one that is not as {@code stratum.cache.sizes-positive breaks @AssertTrue}.
   *
   * @return whether every limit is above 0
   */
  @AssertTrue
  public boolean isSizesPositive() {
    return Stream.concat(
            Stream.of(maxKeySize, maxValueSize),
            caches.values().stream()
                .flatMap(settings -> Stream.of(settings.maxKeySize(), settings.maxValueSize())))
        .allMatch(size -> size == null || size.toBytes() > 0);
  }

  /**
   * Returns whether {@code redis-retry-interval} is from 1s to 5m. Validation reports a value out
   * of that range as {@code stratum.cache.redis-retry-interval-in-range breaks @AssertTrue}.
   *
   * @return whether the interval is in range
   */
  @AssertTrue
  public boolean isRedisRetryIntervalInRange() {
    return redisRetryInterval.compareTo(MIN_REDIS_RETRY_INTERVAL) >= 0
        && redisRetryInterval.compareTo(MAX_REDIS_RETRY_INTERVAL) <= 0;
  }

  /** Returns a cache's own size limit in bytes, or the one for every cache. */
  private long sizeFor(
      String cacheName, Function<CacheSettings, @Nullable DataSize> limit, DataSize everyCache) {
    return Optional.ofNullable(caches.get(cacheName)).map(limit).orElse(everyCache).toBytes();
  }

  /**
   * How Redis cache values are stored, under {@code stratum.cache.serialization.*}: as plain JSON,
   * read back as the cached method's return type.
   *
   * @param allowedPackages Packages whose types Redis caches read, beside the service's own
   *     packages and java, such as com.acme.shared for shared types. Each one covers its
   *     subpackages. Empty by default.
   */
  public record Serialization(List<@NotBlank String> allowedPackages) {

    /** Keeps the packages a fixed list, empty when none are set. */
    public Serialization {
      allowedPackages = allowedPackages == null ? List.of() : List.copyOf(allowedPackages);
    }
  }

  /**
   * The settings for one cache, under {@code stratum.cache.caches.<name>.*}. Each one left unset
   * takes the value for every cache.
   *
   * @param maxKeySize The longest key of this cache stored as it is. Above 0.
   * @param maxValueSize The largest value this cache stores, such as 5MB for a cache of large
   *     reports. Above 0.
   */
  @Builder(toBuilder = true)
  public record CacheSettings(@Nullable DataSize maxKeySize, @Nullable DataSize maxValueSize) {}
}
