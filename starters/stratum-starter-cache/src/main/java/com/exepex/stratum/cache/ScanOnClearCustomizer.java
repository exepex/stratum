package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.RedisCacheManager.RedisCacheManagerBuilder;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Makes Spring Boot's Redis caches clear with SCAN in batches of 1,000 keys, so that {@code
 * allEntries} never blocks Redis with KEYS. It keeps every other setting, statistics included, and
 * takes no lock, as Spring Boot's writer does.
 */
@StratumInternal
@RequiredArgsConstructor
public class ScanOnClearCustomizer implements RedisCacheManagerBuilderCustomizer {

  /** The connection to Redis that Spring Boot's cache manager uses. */
  private final RedisConnectionFactory connectionFactory;

  /**
   * Sets a writer that clears with SCAN.
   *
   * @param builder Spring Boot's builder of the Redis cache manager
   */
  @Override
  public void customize(RedisCacheManagerBuilder builder) {
    builder.cacheWriter(
        RedisCacheWriter.nonLockingRedisCacheWriter(
            connectionFactory, BatchStrategies.scan(CacheConstants.SCAN_BATCH_SIZE)));
  }
}
