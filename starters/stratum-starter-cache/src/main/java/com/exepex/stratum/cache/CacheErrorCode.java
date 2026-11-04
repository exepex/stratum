package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.Severity;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The codes of the cache module, each listed with its fix in {@code docs/guides/error-codes.md}.
 * Every error here stops the application at startup.
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
          + "=false.");

  /** The code, such as {@code SX-CACHE-1001}. */
  private final String code;

  /** Whether the operation failed or only needs attention. */
  private final Severity severity;

  /** What went wrong, in fixed text. */
  private final String description;

  /** How to fix it, as the Fix column of the error codes page starts. */
  private final String fix;
}
