package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import lombok.experimental.UtilityClass;

/** The property keys and names the cache module uses (ST-ENG-45). */
@StratumInternal
@UtilityClass
public class CacheConstants {

  /** The module's name, as its switch {@code stratum.cache.enabled} spells it. */
  public static final String MODULE = "cache";

  /** The module's on/off property. */
  public static final String ENABLED = StratumModuleConstants.enabledProperty(MODULE);

  /** The part of the error codes that names this module, as in {@code SX-CACHE-1001}. */
  public static final String ERROR_CODE_MODULE = "CACHE";

  /** The service's name, which Stratum's Redis key prefix starts with. */
  public static final String APPLICATION_NAME = "spring.application.name";

  /** How long a Redis cache entry lives. */
  public static final String TIME_TO_LIVE = "spring.cache.redis.time-to-live";

  /** The text every Redis cache key starts with. */
  public static final String KEY_PREFIX = "spring.cache.redis.key-prefix";

  /** Whether Redis cache keys carry a prefix at all. */
  public static final String USE_KEY_PREFIX = "spring.cache.redis.use-key-prefix";

  /** Whether Redis caches count their hits and misses, for the cache metrics. */
  public static final String ENABLE_STATISTICS = "spring.cache.redis.enable-statistics";

  /** How long a Redis command may take. */
  public static final String REDIS_TIMEOUT = "spring.data.redis.timeout";

  /** How long connecting to Redis may take. */
  public static final String REDIS_CONNECT_TIMEOUT = "spring.data.redis.connect-timeout";

  /** Stratum's default Redis key prefix: the service's name and a colon. */
  public static final String SERVICE_NAME_PREFIX = "${" + APPLICATION_NAME + "}:";

  /** A class of Spring Data Redis, whose presence means the service can cache in Redis. */
  public static final String SPRING_DATA_REDIS =
      "org.springframework.data.redis.connection.RedisConnectionFactory";

  /** How many keys one SCAN call asks Redis for when a cache is cleared. */
  public static final int SCAN_BATCH_SIZE = 1000;

  /** The name of the bean that makes Redis caches clear with SCAN. */
  public static final String SCAN_ON_CLEAR = "stratumScanOnClear";
}
