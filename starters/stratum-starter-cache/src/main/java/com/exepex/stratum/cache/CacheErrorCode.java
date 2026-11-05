package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.Severity;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The codes of the cache module, each listed with its fix in {@code docs/guides/error-codes.md}.
 * Every error here stops the application at startup, apart from the warnings {@code SX-CACHE-1002},
 * {@code SX-CACHE-1003} and {@code SX-CACHE-1006}, which are logged while the calls carry on.
 */
@StratumInternal
@Getter
@RequiredArgsConstructor
public enum CacheErrorCode implements ErrorCode {

  /** The service has no name, so Stratum's Redis key prefix would not tell its keys apart. */
  APPLICATION_NAME_MISSING(
      "SX-CACHE-1001",
      Severity.ERROR,
      CacheConstants.APPLICATION_NAME
          + " is not set; the cache module starts every Redis cache key with it",
      "Set "
          + CacheConstants.APPLICATION_NAME
          + ", or set your own "
          + CacheConstants.KEY_PREFIX
          + ", or turn the module off with "
          + CacheConstants.ENABLED
          + "=false."),

  /** A cache call failed, such as a read or write during a Redis outage; the call ran anyway. */
  CACHE_OPERATION_FAILED(
      "SX-CACHE-1002",
      Severity.WARNING,
      "A cache operation failed, and the call ran without the cache",
      ""),

  /** A cache name over the limit of {@code stratum.cache.max-caches} was refused. */
  CACHE_LIMIT_REACHED(
      "SX-CACHE-1003",
      Severity.WARNING,
      "A cache name was refused, over the limit of "
          + CacheConstants.MAX_CACHES
          + "; its calls run without a cache",
      ""),

  /** Redis could not be reached or timed out, so Redis caches skip Redis for a while. */
  REDIS_UNREACHABLE(
      "SX-CACHE-1006",
      Severity.WARNING,
      "Redis could not be reached or did not answer in time; Redis caches skip it for "
          + CacheConstants.REDIS_RETRY_INTERVAL
          + ", then one call tries again",
      "");

  /** The code, such as {@code SX-CACHE-1001}. */
  private final String code;

  /** Whether the operation failed or only needs attention. */
  private final Severity severity;

  /** What went wrong, in fixed text. */
  private final String description;

  /** How to fix it, as the Fix column of the error codes page starts. */
  private final String fix;
}
