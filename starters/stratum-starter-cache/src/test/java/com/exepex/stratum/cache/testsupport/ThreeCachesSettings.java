package com.exepex.stratum.cache.testsupport;

import com.exepex.stratum.cache.StratumCacheProperties;
import com.exepex.stratum.cache.StratumCachePropertiesCustomizer;
import java.time.Duration;

/**
 * A customizer that admits only three caches and retries Redis after one second, whatever the files
 * say, for the customizer test.
 */
public class ThreeCachesSettings implements StratumCachePropertiesCustomizer {

  /** How many caches the customizer admits. */
  public static final int MAX_CACHES = 3;

  /** How long the customizer has Redis caches skip Redis after a failure. */
  public static final Duration REDIS_RETRY_INTERVAL = Duration.ofSeconds(1);

  @Override
  public StratumCacheProperties customize(StratumCacheProperties properties) {
    return properties.toBuilder()
        .maxCaches(MAX_CACHES)
        .redisRetryInterval(REDIS_RETRY_INTERVAL)
        .build();
  }
}
