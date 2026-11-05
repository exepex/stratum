package com.exepex.stratum.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;

@DisplayName("CacheKeyMatcher finds the cache a Redis key belongs to")
class CacheKeyMatcherTest {

  /** The key prefix Stratum gives a service named orders. */
  private static final String PREFIX = "orders:";

  /** The cache name most tests find. */
  private static final String ORDERS = "orders";

  /** A matcher for two caches, one name the start of the other. */
  private final CacheKeyMatcher matcher = new CacheKeyMatcher(PREFIX, Set.of("order", ORDERS));

  /** Returns the key's bytes in a buffer, as Lettuce gives them. */
  private static ByteBuffer key(String text) {
    return ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
  }

  @Nested
  @DisplayName("with a key prefix")
  class WithPrefix {

    /** Keys and the cache each belongs to, or null for none. */
    private static final List<Arguments> KEYS =
        List.of(
            arguments("orders:orders::42", ORDERS),
            arguments("orders:order::42", "order"),
            arguments("orders:order::orders::42", "order"),
            arguments("orders:products::42", null),
            arguments("orders:orders:42", null),
            arguments("orders:ordersandmore::42", null),
            arguments("billing:orders::42", null),
            arguments("orders:", null),
            arguments("orders:orders::", ORDERS),
            arguments("orders:::", null),
            arguments("ord", null));

    @ParameterizedTest(name = "{0} -> {1}")
    @FieldSource("KEYS")
    @DisplayName("a key matches only a known name followed by ::, right after the prefix")
    void matchesKnownNames(String key, String cacheName) {
      assertThat(matcher.cacheNameOf(key(key))).isEqualTo(cacheName);
    }

    @Test
    @DisplayName("the buffer's position is respected and left where it was")
    void readsFromPosition() {
      var buffer = key("xxorders:orders::42");
      buffer.position(2);

      assertThat(matcher.cacheNameOf(buffer)).isEqualTo(ORDERS);
      assertThat(buffer.position()).isEqualTo(2);
    }

    @Test
    @DisplayName("a command without a key matches nothing")
    void noKey() {
      assertThat(matcher.cacheNameOf(null)).isNull();
    }
  }

  @Nested
  @DisplayName("without a key prefix or names")
  class Edges {

    @Test
    @DisplayName("with an empty prefix, the name starts the key")
    void emptyPrefix() {
      var unprefixed = new CacheKeyMatcher("", Set.of(ORDERS));

      assertThat(unprefixed.cacheNameOf(key("orders::42"))).isEqualTo(ORDERS);
    }

    @Test
    @DisplayName("when keys carry no cache name, nothing matches")
    void noCacheNameInKeys() {
      var noPrefix = new CacheKeyMatcher(null, Set.of(ORDERS));

      assertThat(noPrefix.cacheNameOf(key("orders::42"))).isNull();
    }

    @Test
    @DisplayName("with no names, nothing matches")
    void noNames() {
      assertThat(new CacheKeyMatcher(PREFIX, Set.of()).cacheNameOf(key("orders:orders::42")))
          .isNull();
    }
  }
}
