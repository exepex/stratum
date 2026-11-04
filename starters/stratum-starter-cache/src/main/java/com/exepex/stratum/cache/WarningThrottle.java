package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;

/**
 * Admits one warning per cache name per interval, so an outage writes one line per cache rather
 * than one per failed call. The map of last warnings holds at most {@code maxCaches + 1} slots: a
 * name asked about past that shares the {@code _over-limit} slot, which keeps the map, and every
 * meter tag folded through {@link #tagValueFor}, bounded no matter how many names a service
 * generates.
 */
@StratumInternal
@RequiredArgsConstructor
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
   * access holds {@link #lock}, so the size check and the insert stay one step.
   */
  private final Map<String, Instant> lastWarningAt = new ConcurrentHashMap<>();

  /** Guards {@link #lastWarningAt}. */
  private final ReentrantLock lock = new ReentrantLock();

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
    lock.lock();
    try {
      var slot = slotFor(cacheName);
      var last = lastWarningAt.get(slot);
      if (last != null && !NEVER_WARNED.equals(last) && last.plus(interval).isAfter(now)) {
        return false;
      }
      lastWarningAt.put(slot, now);
      return true;
    } finally {
      lock.unlock();
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
    lock.lock();
    try {
      var slot = slotFor(cacheName);
      lastWarningAt.putIfAbsent(slot, NEVER_WARNED);
      return slot;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns the name's own slot while there is room, otherwise the shared one. Never null. The
   * shared slot is the extra one, so it never takes a slot from the admitted names.
   */
  private String slotFor(String cacheName) {
    var ownSlots =
        lastWarningAt.size() - (lastWarningAt.containsKey(CacheConstants.OVER_LIMIT) ? 1 : 0);
    if (ownSlots < maxCaches || lastWarningAt.containsKey(cacheName)) {
      return cacheName;
    }
    return CacheConstants.OVER_LIMIT;
  }
}
