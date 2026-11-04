package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;

/**
 * Counts the cache layer's failures and skips (refused cache names, hashed keys and values too
 * large to store), so that the decorator, the handler and the resolver never name a Micrometer type
 * and stay loadable without {@code micrometer-core}.
 */
@StratumInternal
public interface CacheCounters {

  /**
   * Counts one failed cache operation.
   *
   * @param cache the cache's name, or {@code _over-limit} past the limit
   * @param operation the operation that failed, such as {@code get}
   */
  void failure(String cache, String operation);

  /**
   * Counts one skip: a refused cache name, a key stored under its hash or a value not stored.
   *
   * @param cache the cache's name, or {@code _over-limit} for a refused name
   * @param reason why, such as {@code cache-limit}, {@code key-hashed} or {@code value-too-large}
   */
  void skip(String cache, String reason);

  /**
   * Returns counters that count nothing, for a service without Micrometer.
   *
   * @return the no-op counters
   */
  static CacheCounters noop() {
    return Noop.INSTANCE;
  }

  /** The counters that count nothing. */
  @StratumInternal
  enum Noop implements CacheCounters {

    /** The one instance. */
    INSTANCE;

    @Override
    public void failure(String cache, String operation) {
      // Counts nothing.
    }

    @Override
    public void skip(String cache, String reason) {
      // Counts nothing.
    }
  }
}
