package com.exepex.stratum.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.exepex.stratum.cache.testsupport.HashedKeys;
import com.exepex.stratum.cache.testsupport.RecordingCacheCounters;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cache.Cache.ValueRetrievalException;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("The Redis cache writer bounds the size of each key and value")
class SizeLimitedRedisCacheWriterTest {

  /** The cache the tests write to. */
  private static final String CACHE = "products";

  /** A cache with its own, larger limits. */
  private static final String REPORTS = "reports";

  /** The key prefix Spring Boot gives a key of {@value #CACHE} with Stratum's defaults. */
  private static final String PREFIX = "orders-service:" + CACHE + "::";

  /** The default key limit, 1 KiB. */
  private static final int KEY_LIMIT = 1024;

  /** The default value limit, 1 MiB. */
  private static final int VALUE_LIMIT = 1024 * 1024;

  /** How many hex digits a SHA-256 hash has. */
  private static final int SHA_256_HEX_DIGITS = 64;

  /** The time to live the tests pass. */
  private static final Duration TTL = Duration.ofMinutes(10);

  /** A key of exactly the limit. */
  private static final byte[] KEY_AT_LIMIT = key(PREFIX, KEY_LIMIT);

  /** A key one byte over the limit. */
  private static final byte[] LONG_KEY = key(PREFIX, KEY_LIMIT + 1);

  /** {@link #LONG_KEY} as the writer stores it. */
  private static final byte[] HASHED_LONG_KEY = hashed(PREFIX, LONG_KEY);

  /** A small value. */
  private static final byte[] VALUE = bytes("price-of-sku-1");

  /** A value one byte over the limit. */
  private static final byte[] LARGE_VALUE = new byte[VALUE_LIMIT + 1];

  /** Every call that takes a key, given the writer and the key, so each is checked to hash. */
  private static final List<Arguments> KEY_CALLS =
      List.of(
          call("get", (target, key) -> target.get(CACHE, key)),
          call("get with time to live", (target, key) -> target.get(CACHE, key, TTL)),
          call("retrieve", (target, key) -> target.retrieve(CACHE, key, TTL)),
          call("put", (target, key) -> target.put(CACHE, key, VALUE, TTL)),
          call("store", (target, key) -> target.store(CACHE, key, VALUE, TTL)),
          call("putIfAbsent", (target, key) -> target.putIfAbsent(CACHE, key, VALUE, TTL)),
          call("evict", (target, key) -> target.evict(CACHE, key)),
          call("evictIfPresent", (target, key) -> target.evictIfPresent(CACHE, key)));

  /** The writer the decorator wraps, which talks to Redis in a real service. */
  private final RedisCacheWriter delegate = mock(RedisCacheWriter.class);

  /** What the decorator counted. */
  private final RecordingCacheCounters counters = new RecordingCacheCounters();

  /** The decorator with the defaults, and 5 MB values and 2 KB keys for {@value #REPORTS}. */
  private final SizeLimitedRedisCacheWriter writer =
      new SizeLimitedRedisCacheWriter(
          delegate,
          StratumCacheProperties.resolve(
              new MockEnvironment()
                  .withProperty("stratum.cache.caches." + REPORTS + ".max-value-size", "5MB")
                  .withProperty("stratum.cache.caches." + REPORTS + ".max-key-size", "2KB")),
          counters);

  @Nested
  @DisplayName("a key over its limit is stored under its hash")
  class Keys {

    @ParameterizedTest(name = "{0} bytes")
    @ValueSource(ints = {KEY_LIMIT - 1, KEY_LIMIT})
    @DisplayName("a key up to the limit is used as it is")
    void keepsKeyUpToLimit(int length) {
      var key = key(PREFIX, length);

      writer.get(CACHE, key);

      verify(delegate).get(CACHE, key);
      assertThat(counters.skips(CACHE, CacheConstants.REASON_KEY_HASHED)).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("com.exepex.stratum.cache.SizeLimitedRedisCacheWriterTest#KEY_CALLS")
    @DisplayName("a key one byte over is hashed after its prefix, on every call, and counted")
    void hashesLongKeyOnEveryCall(String name, BiConsumer<RedisCacheWriter, byte[]> call) {
      call.accept(writer, LONG_KEY);

      call.accept(verify(delegate), HASHED_LONG_KEY);
      assertThat(new String(HASHED_LONG_KEY, StandardCharsets.UTF_8))
          .startsWith(PREFIX + "sha256:")
          .hasSize(PREFIX.length() + "sha256:".length() + SHA_256_HEX_DIGITS);
      assertThat(counters.skips(CACHE, CacheConstants.REASON_KEY_HASHED)).isEqualTo(1);
    }

    @Test
    @DisplayName("a key without its cache's prefix is stored as the hash alone")
    void hashesKeyWithoutPrefix() {
      var key = key("", KEY_LIMIT + 1);

      writer.get(CACHE, key);

      verify(delegate).get(CACHE, hashed("", key));
    }

    @Test
    @DisplayName("a key with the cache's name again inside keeps only the first prefix")
    void keepsFirstPrefixOnly() {
      var key = key(PREFIX + PREFIX, KEY_LIMIT + 1);

      writer.get(CACHE, key);

      verify(delegate).get(CACHE, hashed(PREFIX, key));
    }

    @Test
    @DisplayName("a cache's own key limit wins")
    void usesCacheKeyLimit() {
      var key = key("orders-service:" + REPORTS + "::", 2 * KEY_LIMIT);

      writer.get(REPORTS, key);

      verify(delegate).get(REPORTS, key);
    }
  }

  @Nested
  @DisplayName("a value over its limit is not stored")
  class Values {

    @Test
    @DisplayName("a value of the limit is stored")
    void storesValueAtLimit() {
      var value = new byte[VALUE_LIMIT];

      writer.put(CACHE, KEY_AT_LIMIT, value, TTL);

      verify(delegate).put(CACHE, KEY_AT_LIMIT, value, TTL);
    }

    @Test
    @DisplayName("a put one byte over removes the older entry instead, and is counted")
    void evictsOnLargePut() {
      writer.put(CACHE, KEY_AT_LIMIT, LARGE_VALUE, TTL);

      verify(delegate, never()).put(any(), any(), any(), any());
      verify(delegate).evict(CACHE, KEY_AT_LIMIT);
      assertThat(counters.skips(CACHE, CacheConstants.REASON_VALUE_TOO_LARGE)).isEqualTo(1);
    }

    @Test
    @DisplayName(
        "a store or putIfAbsent one byte over writes nothing and is counted; putIfAbsent returns"
            + " what the cache holds")
    void skipsLargeStoreAndPutIfAbsent() {
      when(delegate.get(CACHE, KEY_AT_LIMIT, TTL)).thenReturn(VALUE);

      assertThat(writer.store(CACHE, KEY_AT_LIMIT, LARGE_VALUE, TTL)).isCompleted();
      assertThat(writer.putIfAbsent(CACHE, KEY_AT_LIMIT, LARGE_VALUE, TTL)).isSameAs(VALUE);

      verify(delegate, never()).store(any(), any(), any(), any());
      verify(delegate, never()).putIfAbsent(any(), any(), any(), any());
      assertThat(counters.skips(CACHE, CacheConstants.REASON_VALUE_TOO_LARGE)).isEqualTo(2);
    }

    @Test
    @DisplayName("a cache's own 5 MB limit stores a 2 MB value")
    void usesCacheValueLimit() {
      var value = new byte[2 * VALUE_LIMIT];

      writer.put(REPORTS, KEY_AT_LIMIT, value, TTL);

      verify(delegate).put(REPORTS, KEY_AT_LIMIT, value, TTL);
    }
  }

  @Nested
  @DisplayName("a value loaded on a miss goes through the wrapped writer's own load")
  class LoadThrough {

    @Test
    @DisplayName("a value within the limit is loaded and stored by the wrapped writer, at once")
    void loadsThroughDelegate() {
      loadOnMiss();

      assertThat(writer.get(CACHE, LONG_KEY, () -> VALUE, TTL, false)).isEqualTo(VALUE);

      verify(delegate).get(eq(CACHE), eq(HASHED_LONG_KEY), any(), eq(TTL), eq(false));
      verify(delegate, never()).put(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a value over the limit is returned, never reaches the wrapped writer's write")
    void returnsLargeLoadedValue() {
      loadOnMiss();

      assertThat(writer.get(CACHE, KEY_AT_LIMIT, () -> LARGE_VALUE, TTL, false))
          .isSameAs(LARGE_VALUE);

      verify(delegate, never()).store(any(), any(), any(), any());
      assertThat(counters.skips(CACHE, CacheConstants.REASON_VALUE_TOO_LARGE)).isEqualTo(1);
    }

    @Test
    @DisplayName("the loader's own error reaches the caller unchanged")
    void keepsLoaderError() {
      loadOnMiss();
      var loaderError =
          new ValueRetrievalException("sku-1", () -> null, new IllegalStateException());

      assertThatExceptionOfType(ValueRetrievalException.class)
          .isThrownBy(
              () ->
                  writer.get(
                      CACHE,
                      KEY_AT_LIMIT,
                      () -> {
                        throw loaderError;
                      },
                      TTL,
                      false))
          .isSameAs(loaderError);
    }

    /**
     * Makes the wrapped writer's load-through miss, call the loader and, as Spring Data Redis does,
     * write what it returned in the same call.
     */
    @SuppressWarnings("unchecked")
    private void loadOnMiss() {
      when(delegate.get(any(), any(), any(Supplier.class), any(), anyBoolean()))
          .thenAnswer(
              invocation -> {
                byte[] value = invocation.<Supplier<byte[]>>getArgument(2).get();
                delegate.store(CACHE, invocation.getArgument(1), value, TTL);
                return value;
              });
    }
  }

  @Nested
  @DisplayName("every other call is forwarded")
  class Forwarding {

    @Test
    @DisplayName("with a statistics collector, the writer keeps its limits")
    void keepsLimitsWithStatistics() {
      var collector = CacheStatisticsCollector.create();
      var collecting = mock(RedisCacheWriter.class);
      when(delegate.withStatisticsCollector(collector)).thenReturn(collecting);

      var withStatistics = writer.withStatisticsCollector(collector);
      withStatistics.get(CACHE, LONG_KEY);

      verify(collecting).get(CACHE, HASHED_LONG_KEY);
    }

    @Test
    @DisplayName("clears, statistics and connection callbacks reach the wrapped writer")
    @SuppressWarnings("unchecked")
    void forwardsOtherCalls() {
      var pattern = bytes(PREFIX + "*");
      Function<RedisConnection, Object> callback = mock(Function.class);

      writer.clear(CACHE, pattern);
      writer.invalidate(CACHE, pattern);
      writer.clearStatistics(CACHE);
      writer.getCacheStatistics(CACHE);
      writer.supportsAsyncRetrieve();
      writer.execute(callback);

      verify(delegate).clear(CACHE, pattern);
      verify(delegate).invalidate(CACHE, pattern);
      verify(delegate).clearStatistics(CACHE);
      verify(delegate).getCacheStatistics(CACHE);
      verify(delegate).supportsAsyncRetrieve();
      verify(delegate).execute(callback);
    }
  }

  /** Returns a named call for the parameterized key test. */
  private static Arguments call(String name, BiConsumer<RedisCacheWriter, byte[]> call) {
    return arguments(name, call);
  }

  /** Returns a key of the given length in bytes that starts with the prefix. */
  private static byte[] key(String prefix, int length) {
    return bytes(prefix + "k".repeat(length - prefix.length()));
  }

  /** Returns the key as the writer stores it when it is over the limit. */
  private static byte[] hashed(String prefix, byte[] key) {
    return bytes(prefix + "sha256:" + HashedKeys.sha256Hex(key));
  }

  /** Returns the text's bytes. */
  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
