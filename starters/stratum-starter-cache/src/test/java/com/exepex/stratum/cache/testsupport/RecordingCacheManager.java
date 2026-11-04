package com.exepex.stratum.cache.testsupport;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * A cache manager that creates a {@link StubCache} per name on demand and records which names were
 * asked, so a test sees how many caches a resolver created.
 */
public class RecordingCacheManager implements CacheManager {

  /** The caches created so far, by name. */
  private final Map<String, StubCache> caches = new ConcurrentHashMap<>();

  /** Whether the created caches fail their reads. */
  private final boolean failReads;

  /** Whether the caches created fail their writes. */
  private final boolean failWrites;

  /** Creates a manager whose caches never fail. */
  public RecordingCacheManager() {
    this(false, false);
  }

  /**
   * Creates a manager whose caches fail as asked, as during a Redis outage.
   *
   * @param failReads whether reads fail before any load
   * @param failWrites whether writes fail, and the write after a load with them
   */
  public RecordingCacheManager(boolean failReads, boolean failWrites) {
    this.failReads = failReads;
    this.failWrites = failWrites;
  }

  @Override
  public @Nullable Cache getCache(String name) {
    return caches.computeIfAbsent(name, key -> new StubCache(key, failReads, failWrites));
  }

  @Override
  public Set<String> getCacheNames() {
    return Set.copyOf(caches.keySet());
  }

  /**
   * Returns how many caches were created.
   *
   * @return the number of created caches
   */
  public int createdCount() {
    return caches.size();
  }

  /**
   * Returns the cache created for a name, or {@code null}.
   *
   * @param name the cache's name
   * @return the cache, or {@code null} when none was created
   */
  public @Nullable StubCache created(String name) {
    return caches.get(name);
  }
}
