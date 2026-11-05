package com.exepex.stratum.cache.testsupport;

import com.exepex.stratum.cache.StratumCacheProperties;
import com.exepex.stratum.cache.StratumCachePropertiesCustomizer;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A customizer that admits only three caches, retries Redis after one second, allows one more
 * package, bounds the reports cache and stores Caffeine nulls, whatever the files say, for the
 * customizer test.
 */
public class ThreeCachesSettings implements StratumCachePropertiesCustomizer {

  /** How many caches the customizer admits. */
  public static final int MAX_CACHES = 3;

  /** How long the customizer has Redis caches skip Redis after a failure. */
  public static final Duration REDIS_RETRY_INTERVAL = Duration.ofSeconds(1);

  /** How many failures in a row the customizer lets make Redis caches skip Redis. */
  public static final int REDIS_FAILURE_THRESHOLD = 2;

  /** The package the customizer allows. */
  public static final String ALLOWED_PACKAGE = "com.acme.shared";

  /** The most entries the customizer gives the reports cache. */
  public static final long REPORTS_MAX_ENTRIES = 50;

  @Override
  public StratumCacheProperties customize(StratumCacheProperties properties) {
    return properties.toBuilder()
        .maxCaches(MAX_CACHES)
        .redisRetryInterval(REDIS_RETRY_INTERVAL)
        .redisFailureThreshold(REDIS_FAILURE_THRESHOLD)
        .serialization(new StratumCacheProperties.Serialization(List.of(ALLOWED_PACKAGE)))
        .caffeine(new StratumCacheProperties.CaffeineSettings(true))
        .caches(
            Map.of(
                "reports",
                StratumCacheProperties.CacheSettings.builder()
                    .maxEntries(REPORTS_MAX_ENTRIES)
                    .build()))
        .build();
  }
}
