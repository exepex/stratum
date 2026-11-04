package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnection;

/**
 * A Redis cache writer that bounds the bytes of each entry and forwards everything else. A key over
 * its cache's {@code max-key-size} is stored under {@code <prefix><name>::sha256:<hex>}, on reads,
 * writes and evicts alike, so it still hits and is still cleared. A value over {@code
 * max-value-size} is not stored and the caller gets it back as usual. Each is counted in {@code
 * stratum.cache.skips}. Sizes are checked on the caller's thread.
 */
@StratumInternal
@RequiredArgsConstructor
public class SizeLimitedRedisCacheWriter implements RedisCacheWriter {

  /** What a hashed key carries after the prefix, before the hash. */
  private static final byte[] HASH_MARK = "sha256:".getBytes(StandardCharsets.US_ASCII);

  /** What ends the prefix of a key in Spring Data Redis: {@code <prefix><name>::}. */
  private static final String PREFIX_END = "::";

  /** The writer that talks to Redis. */
  private final RedisCacheWriter delegate;

  /** The size limits of each cache. */
  private final StratumCacheProperties properties;

  /** Counts the hashed keys and the values not stored. */
  private final CacheCounters counters;

  @Override
  public byte @Nullable [] get(String name, byte[] key) {
    return delegate.get(name, limitedKey(name, key));
  }

  @Override
  public byte @Nullable [] get(String name, byte[] key, @Nullable Duration ttl) {
    return delegate.get(name, limitedKey(name, key), ttl);
  }

  /**
   * Reads the entry or loads and stores it through the wrapped writer, which writes a value within
   * the limit at once on the same connection. A loaded value over the limit is returned and not
   * stored.
   */
  @Override
  public byte[] get(
      String name,
      byte[] key,
      Supplier<byte[]> valueLoader,
      @Nullable Duration ttl,
      boolean timeToIdleEnabled) {
    var oversized = new AtomicReference<byte[]>();
    try {
      return delegate.get(
          name,
          limitedKey(name, key),
          refuseOversized(name, valueLoader, oversized),
          ttl,
          timeToIdleEnabled);
    } catch (OversizedValue refused) {
      return oversized.get();
    }
  }

  @Override
  public boolean supportsAsyncRetrieve() {
    return delegate.supportsAsyncRetrieve();
  }

  @Override
  public CompletableFuture<byte[]> retrieve(String name, byte[] key, @Nullable Duration ttl) {
    return delegate.retrieve(name, limitedKey(name, key), ttl);
  }

  /**
   * Stores the value, or, when it is over the limit, removes the key instead, so that an older
   * value never outlives a newer one that was too large. Spring Data Redis may remove it in the
   * background.
   */
  @Override
  public void put(String name, byte[] key, byte[] value, @Nullable Duration ttl) {
    var storedKey = limitedKey(name, key);
    if (fits(name, value)) {
      delegate.put(name, storedKey, value, ttl);
    } else {
      delegate.evict(name, storedKey);
    }
  }

  /** Stores the value of a miss; one over the limit is not stored. */
  @Override
  public CompletableFuture<Void> store(
      String name, byte[] key, byte[] value, @Nullable Duration ttl) {
    return fits(name, value)
        ? delegate.store(name, limitedKey(name, key), value, ttl)
        : CompletableFuture.completedFuture(null);
  }

  /**
   * Stores the value when the key is absent. A value over the limit is not stored; what the cache
   * holds under the key, if anything, is returned, as when the key is present.
   */
  @Override
  public byte @Nullable [] putIfAbsent(
      String name, byte[] key, byte[] value, @Nullable Duration ttl) {
    var storedKey = limitedKey(name, key);
    return fits(name, value)
        ? delegate.putIfAbsent(name, storedKey, value, ttl)
        : delegate.get(name, storedKey, ttl);
  }

  @Override
  public void evict(String name, byte[] key) {
    delegate.evict(name, limitedKey(name, key));
  }

  @Override
  public boolean evictIfPresent(String name, byte[] key) {
    return delegate.evictIfPresent(name, limitedKey(name, key));
  }

  @Override
  public void clear(String name, byte[] pattern) {
    delegate.clear(name, pattern);
  }

  @Override
  public boolean invalidate(String name, byte[] pattern) {
    return delegate.invalidate(name, pattern);
  }

  @Override
  public void clearStatistics(String name) {
    delegate.clearStatistics(name);
  }

  @Override
  public <T extends @Nullable Object> T execute(Function<RedisConnection, T> callback) {
    return delegate.execute(callback);
  }

  @Override
  public RedisCacheWriter withStatisticsCollector(CacheStatisticsCollector collector) {
    return new SizeLimitedRedisCacheWriter(
        delegate.withStatisticsCollector(collector), properties, counters);
  }

  @Override
  public CacheStatistics getCacheStatistics(String cacheName) {
    return delegate.getCacheStatistics(cacheName);
  }

  /** Returns the key as it is, or, when it is over the limit, hashed after its prefix. */
  private byte[] limitedKey(String name, byte[] key) {
    if (key.length <= properties.maxKeyBytesFor(name)) {
      return key;
    }
    counters.skip(name, CacheConstants.REASON_KEY_HASHED);
    var prefixLength = prefixLength(name, key);
    var hash = HexFormat.of().formatHex(sha256(key)).getBytes(StandardCharsets.US_ASCII);
    var hashedKey = Arrays.copyOf(key, prefixLength + HASH_MARK.length + hash.length);
    System.arraycopy(HASH_MARK, 0, hashedKey, prefixLength, HASH_MARK.length);
    System.arraycopy(hash, 0, hashedKey, prefixLength + HASH_MARK.length, hash.length);
    return hashedKey;
  }

  /**
   * Returns the length of the key's prefix, up to and including the first {@code <name>::}, or 0
   * when the key has none, as with a prefix of the service's own that leaves out the name.
   */
  private static int prefixLength(String name, byte[] key) {
    var prefixEnd = (name + PREFIX_END).getBytes(StandardCharsets.UTF_8);
    for (var start = 0; start <= key.length - prefixEnd.length; start++) {
      if (Arrays.equals(key, start, start + prefixEnd.length, prefixEnd, 0, prefixEnd.length)) {
        return start + prefixEnd.length;
      }
    }
    return 0;
  }

  /** Returns whether the value is within its cache's limit, counting it when it is not. */
  private boolean fits(String name, byte[] value) {
    if (value.length <= properties.maxValueBytesFor(name)) {
      return true;
    }
    counters.skip(name, CacheConstants.REASON_VALUE_TOO_LARGE);
    return false;
  }

  /**
   * Wraps the loader so that a value over the limit is kept in {@code oversized} and the wrapped
   * writer is stopped before it stores it.
   */
  private Supplier<byte[]> refuseOversized(
      String name, Supplier<byte[]> valueLoader, AtomicReference<byte[]> oversized) {
    return () -> {
      var value = valueLoader.get();
      if (!fits(name, value)) {
        oversized.set(value);
        throw OversizedValue.INSTANCE;
      }
      return value;
    };
  }

  /** Returns the SHA-256 hash of the bytes. */
  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException missing) {
      // Every Java platform must have SHA-256 (MessageDigest's Javadoc).
      throw new IllegalStateException(missing);
    }
  }

  /**
   * Stops the wrapped writer before it stores a loaded value over the limit. One shared instance
   * with no stack trace and no state, because it is caught at once.
   */
  private static final class OversizedValue extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The one instance. */
    static final OversizedValue INSTANCE = new OversizedValue();

    /** Creates the instance, without a stack trace. */
    private OversizedValue() {
      super(null, null, false, false);
    }
  }
}
