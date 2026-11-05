package com.exepex.stratum.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("RedisCacheNames knows the configured and admitted cache names and finds them in keys")
class RedisCacheNamesTest {

  /** A cache named in the settings. */
  private static final String PRODUCTS = "products";

  /** A cache named later, or not at all. */
  private static final String RATES = "rates";

  /** The key prefix of the service. */
  private static final String PREFIX = "orders:";

  /** Returns the bytes of a key. */
  private ByteBuffer key(String key) {
    return ByteBuffer.wrap(key.getBytes(StandardCharsets.UTF_8));
  }

  @Nested
  @DisplayName("when it is created")
  class FromSettings {

    @Test
    @DisplayName("names in spring.cache.cache-names are known from the start, under the key prefix")
    void configuredNamesAreKnown() {
      var environment =
          new MockEnvironment()
              .withProperty(CacheConstants.KEY_PREFIX, PREFIX)
              .withProperty(CacheConstants.CACHE_NAMES, PRODUCTS + "," + RATES);

      var names = RedisCacheNames.resolve(environment);

      assertThat(names.cacheNameOf(key("orders:products::42"))).isEqualTo(PRODUCTS);
      assertThat(names.cacheNameOf(key("orders:rates::EUR"))).isEqualTo(RATES);
      assertThat(names.cacheNameOf(key("orders:other::42"))).isNull();
    }

    @Test
    @DisplayName("with use-key-prefix=false no key is matched, even after a name is added")
    void noPrefixMatchesNothing() {
      var environment =
          new MockEnvironment()
              .withProperty(CacheConstants.USE_KEY_PREFIX, "false")
              .withProperty(CacheConstants.CACHE_NAMES, PRODUCTS);

      var names = RedisCacheNames.resolve(environment);
      names.add(RATES);

      assertThat(names.cacheNameOf(key("products::42"))).isNull();
      assertThat(names.cacheNameOf(key("rates::42"))).isNull();
    }

    @Test
    @DisplayName(
        "the names of a service without Redis spans match no key, even after a name is added")
    void noneMatchesNothing() {
      var names = RedisCacheNames.none();
      names.add(PRODUCTS);

      assertThat(names.cacheNameOf(key("products::42"))).isNull();
    }
  }

  @Nested
  @DisplayName("when the cache layer admits a name")
  class Added {

    /** Names that know only the products cache at first. */
    private final RedisCacheNames names = new RedisCacheNames(PREFIX, List.of(PRODUCTS));

    @Test
    @DisplayName("an added name is found from then on, and the earlier names still are")
    void addedNameIsKnown() {
      assertThat(names.cacheNameOf(key("orders:rates::EUR"))).isNull();

      names.add(RATES);

      assertThat(names.cacheNameOf(key("orders:rates::EUR"))).isEqualTo(RATES);
      assertThat(names.cacheNameOf(key("orders:products::42"))).isEqualTo(PRODUCTS);
    }

    @Test
    @DisplayName("adding a known name again changes nothing")
    void duplicateChangesNothing() {
      names.add(PRODUCTS);

      assertThat(names.cacheNameOf(key("orders:products::42"))).isEqualTo(PRODUCTS);
    }

    @Test
    @DisplayName("a key of the service's own, or no key, belongs to no cache")
    void otherKeysBelongToNoCache() {
      assertThat(names.cacheNameOf(key("orders:42::lines"))).isNull();
      assertThat(names.cacheNameOf(key("session-42"))).isNull();
      assertThat(names.cacheNameOf(null)).isNull();
    }
  }
}
