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

  /** The first part of the module's own settings, as in {@code stratum.cache.max-caches}. */
  public static final String PREFIX = StratumModuleConstants.PREFIX + "." + MODULE;

  /** The most caches the layer admits; names beyond it run uncached. */
  public static final String MAX_CACHES = PREFIX + ".max-caches";

  /** How often one cache's warning is repeated at most. */
  public static final String WARNING_INTERVAL = PREFIX + ".warning-interval";

  /** The longest Redis cache key stored as it is; longer ones are stored under their hash. */
  public static final String MAX_KEY_SIZE = PREFIX + ".max-key-size";

  /** The largest value a Redis cache stores. */
  public static final String MAX_VALUE_SIZE = PREFIX + ".max-value-size";

  /** The counter of cache calls that failed and were handled fail-open. */
  public static final String METRIC_FAILURES = "stratum.cache.failures";

  /** The counter of cache names refused over the limit and of keys and values over their size. */
  public static final String METRIC_SKIPS = "stratum.cache.skips";

  /** The tag with the cache's name. */
  public static final String TAG_CACHE = "cache";

  /** The tag with the operation that failed, such as {@code get}. */
  public static final String TAG_OPERATION = "operation";

  /** The tag with the reason a skip was counted. */
  public static final String TAG_REASON = "reason";

  /** The one tag value and throttle slot that every refused or overflowing name shares. */
  public static final String OVER_LIMIT = "_over-limit";

  /** The reason a name is refused: over the limit of {@code stratum.cache.max-caches}. */
  public static final String REASON_CACHE_LIMIT = "cache-limit";

  /** The reason counted when a key over its size limit is stored under its hash. */
  public static final String REASON_KEY_HASHED = "key-hashed";

  /** The reason counted when a value over its size limit is not stored. */
  public static final String REASON_VALUE_TOO_LARGE = "value-too-large";

  /** The operation tag of a failed read. */
  public static final String OPERATION_GET = "get";

  /** The operation tag of a failed write. */
  public static final String OPERATION_PUT = "put";

  /** The operation tag of a failed evict. */
  public static final String OPERATION_EVICT = "evict";

  /** The operation tag of a failed clear. */
  public static final String OPERATION_CLEAR = "clear";

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

  /** The name of the bean that sets Stratum's Redis cache writer: SCAN clears and size limits. */
  public static final String REDIS_CACHE_WRITER = "stratumRedisCacheWriter";

  /** The name of the filter that keeps the cache layer's hook eager under lazy initialization. */
  public static final String LAZY_INITIALIZATION_FILTER =
      "stratumCacheLayerLazyInitializationExcludeFilter";
}
