package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.StratumException;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.data.redis.cache.RedisCacheManager.RedisCacheManagerBuilder;

/**
 * Stops startup with {@code SX-CACHE-1007} when Spring Boot builds its Redis cache manager and
 * Jackson, which stores the values as JSON, is missing. Spring Boot calls it only when it builds
 * that manager, so a service whose caches are not in Redis starts without Jackson.
 */
@StratumInternal
public class JacksonRequiredCustomizer implements RedisCacheManagerBuilderCustomizer {

  /**
   * Stops startup.
   *
   * @param builder Spring Boot's builder of the Redis cache manager
   * @throws StratumException {@code SX-CACHE-1007} always
   */
  @Override
  public void customize(RedisCacheManagerBuilder builder) {
    throw new StratumException(CacheErrorCode.JACKSON_MISSING);
  }
}
