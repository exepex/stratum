package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Admits one warning per cache name per interval, so an outage writes one line per cache rather
 * than one per failed call. The map of last warnings holds at most {@code maxCaches + 1} slots: a
 * name asked about past that shares the {@code _over-limit} slot, which keeps the map, and every
 * meter tag folded through {@link #tagValueFor}, bounded no matter how many names a service
 * generates.
 */
@StratumInternal
public final class WarningThrottle {

  /** Marks a slot whose name was only counted so far, never warned. */
  private static final Instant NEVER_WARNED = Instant.MIN;

  /** How often one name's warning is repeated at most. */
  private final Duration interval;

  /** Tells the current time; a test sets it. */
  private final Clock clock;

  /** The most names counted or warned on their own; one more slot is the shared one. */
  private final int maxCaches;

  /**
   * When each name, or the shared slot, last warned; {@link #NEVER_WARNED} if only counted. Every
   * access is inside a lock on the map itself, so a plain map is safe.
   */
  private final Map<String, Instant> lastWarningAt = new HashMap<>();

  /**
   * Creates the throttle.
   *
   * @param interval how often one name's warning is repeated at most
   * @param clock tells the current time
   * @param maxCaches the most caches the layer admits, {@code stratum.cache.max-caches}
   */
  public WarningThrottle(Duration interval, Clock clock, int maxCaches) {
    this.interval = interval;
    this.clock = clock;
    this.maxCaches = maxCaches;
  }

  /**
   * Returns whether a warning for the cache may be written now: the first one for its name, or the
   * first after the interval passed. A name past the map's capacity is throttled on the shared
   * {@code _over-limit} slot, while the caller still logs the name itself.
   *
   * @param cacheName the cache's name, or {@code _over-limit} for a refused name
   * @return whether to warn
   */
  public boolean shouldWarn(String cacheName) {
    var now = clock.instant();
    // Failures and refusals are rare next to cache hits, so one short lock serves every thread.
    synchronized (lastWarningAt) {
      var slot = slotFor(cacheName);
      var last = lastWarningAt.get(slot);
      if (last != null && last != NEVER_WARNED && last.plus(interval).isAfter(now)) {
        return false;
      }
      lastWarningAt.put(slot, now);
      return true;
    }
  }

  /**
   * Returns the tag value a failure of the cache is counted under: its own name while the map has
   * room, otherwise {@code _over-limit}, so meter series stay within the cap too.
   *
   * @param cacheName the cache's name
   * @return the tag value to count under
   */
  public String tagValueFor(String cacheName) {
    synchronized (lastWarningAt) {
      var slot = slotFor(cacheName);
      lastWarningAt.putIfAbsent(slot, NEVER_WARNED);
      return slot;
    }
  }

  /** Returns the name's own slot while there is room, otherwise the shared one. Never null. */
  private String slotFor(String cacheName) {
    if (lastWarningAt.size() < maxCaches || lastWarningAt.containsKey(cacheName)) {
      return cacheName;
    }
    return CacheConstants.OVER_LIMIT;
  }
}
