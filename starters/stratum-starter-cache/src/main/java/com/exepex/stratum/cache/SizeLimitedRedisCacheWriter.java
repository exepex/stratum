package com.exepex.stratum.cache;

import com.exepex.stratum.core.api.StratumInternal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnection;

/**
 * A Redis cache writer that bounds the bytes of each entry and forwards everything else. A key over
 * its cache's {@code max-key-size}, or one that already starts with {@code sha256:} after its
 * prefix, is stored under {@code <prefix><name>::sha256:<hex>}, on reads, writes and evicts alike,
 * so it still hits and is still cleared. A value over {@code max-value-size} is not stored and the
 * caller gets it back as usual. Each is counted in {@code stratum.cache.skips}. Sizes are checked
 * on the caller's thread.
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
    var loader = new SizeCheckedLoader(name, valueLoader);
    try {
      return delegate.get(name, limitedKey(name, key), loader, ttl, timeToIdleEnabled);
    } catch (OversizedValue refused) {
      return Objects.requireNonNull(loader.oversized);
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

  /**
   * Returns the key as it is, or hashed after its prefix when it is over the limit or already looks
   * like a hashed key, so that a literal key such as {@code sha256:<hex>} never reads another key's
   * entry.
   */
  private byte[] limitedKey(String name, byte[] key) {
    var limit = properties.maxKeyBytesFor(name);
    var prefixLength = prefixLength(name, key, limit);
    if (key.length <= limit && !hasHashMark(key, prefixLength)) {
      return key;
    }
    counters.skip(name, CacheConstants.REASON_KEY_HASHED);
    var hash = HexFormat.of().formatHex(sha256(key)).getBytes(StandardCharsets.US_ASCII);
    var hashedKey = Arrays.copyOf(key, prefixLength + HASH_MARK.length + hash.length);
    System.arraycopy(HASH_MARK, 0, hashedKey, prefixLength, HASH_MARK.length);
    System.arraycopy(hash, 0, hashedKey, prefixLength + HASH_MARK.length, hash.length);
    return hashedKey;
  }

  /**
   * Returns the length of the key's prefix, up to and including the first {@code <name>::} that
   * ends within the key limit, or 0 when there is none, as with {@code
   * spring.cache.redis.use-key-prefix=false}. Searching only within the limit keeps a hashed key at
   * most the limit plus the hash, whatever the key holds.
   */
  private int prefixLength(String name, byte[] key, long limit) {
    var prefixEnd = (name + PREFIX_END).getBytes(StandardCharsets.UTF_8);
    var lastStart = (int) Math.min(key.length, limit) - prefixEnd.length;
    return IntStream.rangeClosed(0, lastStart)
        .filter(
            start ->
                Arrays.equals(key, start, start + prefixEnd.length, prefixEnd, 0, prefixEnd.length))
        .map(start -> start + prefixEnd.length)
        .findFirst()
        .orElse(0);
  }

  /** Returns whether the key goes on, after its prefix, with the mark of a hashed key. */
  private boolean hasHashMark(byte[] key, int prefixLength) {
    return key.length >= prefixLength + HASH_MARK.length
        && Arrays.equals(
            key, prefixLength, prefixLength + HASH_MARK.length, HASH_MARK, 0, HASH_MARK.length);
  }

  /** Returns whether the value is within its cache's limit, counting it when it is not. */
  private boolean fits(String name, byte[] value) {
    if (value.length <= properties.maxValueBytesFor(name)) {
      return true;
    }
    counters.skip(name, CacheConstants.REASON_VALUE_TOO_LARGE);
    return false;
  }

  /** Returns the SHA-256 hash of the bytes. */
  private byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException missing) {
      // Every Java platform must have SHA-256 (MessageDigest's Javadoc).
      throw new IllegalStateException(missing);
    }
  }

  /**
   * Loads a value for the wrapped writer, and stops that writer before it stores a value over the
   * limit, keeping the value for the caller.
   */
  @RequiredArgsConstructor
  private final class SizeCheckedLoader implements Supplier<byte[]> {

    /** The cache's name. */
    private final String name;

    /** The loader Spring Data Redis passes, which calls the cached method. */
    private final Supplier<byte[]> valueLoader;

    /** The loaded value over the limit; {@code null} until one is loaded. */
    private byte @Nullable [] oversized;

    @Override
    public byte[] get() {
      var value = valueLoader.get();
      if (!fits(name, value)) {
        oversized = value;
        throw OversizedValue.INSTANCE;
      }
      return value;
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
