package com.exepex.stratum.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("CacheTimeoutSettings reads what the cache command timeouts depend on")
class CacheTimeoutSettingsTest {

  /** The global {@code spring.data.redis.timeout} the tests set. */
  private static final Duration GLOBAL = Duration.ofSeconds(1);

  /** The key of the reports cache's own command timeout. */
  private static final String REPORTS_COMMAND_TIMEOUT_KEY =
      CacheConstants.CACHES + ".reports.command-timeout";

  /** An environment with the global timeout and the reports cache's own value, above it. */
  private MockEnvironment environment() {
    return new MockEnvironment()
        .withProperty(CacheConstants.REDIS_TIMEOUT, "1s")
        .withProperty(REPORTS_COMMAND_TIMEOUT_KEY, "3s");
  }

  @Nested
  @DisplayName("from the service's environment")
  class Resolve {

    @Test
    @DisplayName("keys carry the service's key prefix before the cache name")
    void readsKeyPrefix() {
      var settings =
          CacheTimeoutSettings.resolve(
              environment().withProperty(CacheConstants.KEY_PREFIX, "orders:"));

      assertThat(settings.global()).isEqualTo(GLOBAL);
      assertThat(settings.keyPrefix()).isEqualTo("orders:");
      assertThat(settings.matcher().cacheNameOf(key("orders:reports::1"))).isEqualTo("reports");
    }

    @Test
    @DisplayName(
        "with use-key-prefix=false, keys carry no cache name, so every command gets the global"
            + " timeout")
    void noCacheNameWithoutKeyPrefix() {
      var settings =
          CacheTimeoutSettings.resolve(
              environment().withProperty(CacheConstants.USE_KEY_PREFIX, "false"));

      assertThat(settings.keyPrefix()).isNull();
      assertThat(settings.matcher().cacheNameOf(key("reports::1"))).isNull();
    }

    @Test
    @DisplayName("without spring.data.redis.timeout, the global value is Lettuce's own 60 s")
    void lettuceDefaultWithoutGlobal() {
      var settings = CacheTimeoutSettings.resolve(new MockEnvironment());

      assertThat(settings.global()).isEqualTo(CacheConstants.LETTUCE_DEFAULT_TIMEOUT);
    }

    /** Returns the key's bytes in a buffer, as Lettuce gives them. */
    private ByteBuffer key(String text) {
      return ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
    }
  }

  @Nested
  @DisplayName("short timeout caches")
  class ShortTimeoutCaches {

    @Test
    @DisplayName(
        "only a cache below the global value is short; one equal to it or above it is not, so"
            + " its timeout still makes every cache skip Redis")
    void onlyBelowGlobal() {
      var settings =
          CacheTimeoutSettings.resolve(
              environment()
                  .withProperty(CacheConstants.CACHES + ".quotes.command-timeout", "200ms")
                  .withProperty(CacheConstants.CACHES + ".prices.command-timeout", "1s"));

      assertThat(settings.shortTimeoutCaches()).containsExactly("quotes");
    }
  }
}
