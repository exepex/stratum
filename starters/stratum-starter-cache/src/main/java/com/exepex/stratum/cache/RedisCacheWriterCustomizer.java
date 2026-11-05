package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.RedisCacheManager.RedisCacheManagerBuilder;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Gives Spring Boot's Redis caches Stratum's writer: it clears with SCAN in batches of 1,000 keys,
 * so that {@code allEntries} never blocks Redis with KEYS, and it bounds the size of each key and
 * value ({@link SizeLimitedRedisCacheWriter}). It keeps every other setting, statistics included,
 * and takes no lock, as Spring Boot's writer does.
 */
@StratumInternal
@RequiredArgsConstructor
public class RedisCacheWriterCustomizer implements RedisCacheManagerBuilderCustomizer {

  /** The connection to Redis that Spring Boot's cache manager uses. */
  private final RedisConnectionFactory connectionFactory;

  /** The size limits of each cache. */
  private final StratumCacheProperties properties;

  /** Counts the hashed keys and the values not stored. */
  private final CacheCounters counters;

  /**
   * Sets Stratum's writer.
   *
   * @param builder Spring Boot's builder of the Redis cache manager
   */
  @Override
  public void customize(RedisCacheManagerBuilder builder) {
    builder.cacheWriter(
        new SizeLimitedRedisCacheWriter(
            RedisCacheWriter.nonLockingRedisCacheWriter(
                connectionFactory, BatchStrategies.scan(CacheConstants.SCAN_BATCH_SIZE)),
            properties,
            counters));
  }
}
