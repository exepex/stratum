package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Finds which cache a Redis key belongs to. Spring Boot writes a cache key as {@code
 * <key-prefix><cache name>::<key>}, so the matcher checks the prefix, then looks for {@code ::}
 * within the longest known name. It reads at most the prefix, the longest name and two bytes of any
 * key, so a long key costs no more than a short one, and it never keeps or logs a key.
 */
@StratumInternal
public final class CacheKeyMatcher {

  /** The separator Spring Boot writes after the cache name. */
  private static final byte SEPARATOR = ':';

  /** The bytes of the key prefix, or {@code null} when keys carry no cache name. */
  private final byte @Nullable [] prefix;

  /** The names this matcher knows. */
  private final Set<String> cacheNames;

  /** The length of the longest known name, in UTF-8 bytes. */
  private final int longestName;

  /**
   * Creates a matcher for the given names.
   *
   * @param keyPrefix the text before the cache name: {@code spring.cache.redis.key-prefix}, empty
   *     when unset, or {@code null} when keys carry no cache name, as with {@code
   *     spring.cache.redis.use-key-prefix=false}; then nothing matches
   * @param cacheNames the cache names to find
   */
  public CacheKeyMatcher(@Nullable String keyPrefix, Set<String> cacheNames) {
    this.prefix = keyPrefix == null ? null : keyPrefix.getBytes(StandardCharsets.UTF_8);
    this.cacheNames = Set.copyOf(cacheNames);
    this.longestName =
        this.cacheNames.stream()
            .mapToInt(name -> name.getBytes(StandardCharsets.UTF_8).length)
            .max()
            .orElse(0);
  }

  /**
   * Returns the name of the known cache the key belongs to.
   *
   * @param key the key's bytes, read without moving the buffer's position; may be {@code null}
   * @return the cache name, or {@code null} when the key belongs to no known cache
   */
  public @Nullable String cacheNameOf(@Nullable ByteBuffer key) {
    var keyPrefix = prefix;
    if (key == null || keyPrefix == null || longestName == 0 || !startsWith(key, keyPrefix)) {
      return null;
    }
    var nameStart = key.position() + keyPrefix.length;
    var lastSeparator = Math.min(key.limit() - 1, nameStart + longestName + 1);
    for (var index = nameStart + 1; index < lastSeparator; index++) {
      if (key.get(index) == SEPARATOR && key.get(index + 1) == SEPARATOR) {
        return knownName(key, nameStart, index);
      }
    }
    return null;
  }

  /** Returns whether the key starts with the prefix. */
  private boolean startsWith(ByteBuffer key, byte[] prefix) {
    var start = key.position();
    if (key.limit() - start < prefix.length) {
      return false;
    }
    for (var index = 0; index < prefix.length; index++) {
      if (key.get(start + index) != prefix[index]) {
        return false;
      }
    }
    return true;
  }

  /** Returns the name between the two positions when it is known. */
  private @Nullable String knownName(ByteBuffer key, int from, int to) {
    var bytes = new byte[to - from];
    key.get(from, bytes);
    var name = new String(bytes, StandardCharsets.UTF_8);
    return cacheNames.contains(name) ? name : null;
  }
}
