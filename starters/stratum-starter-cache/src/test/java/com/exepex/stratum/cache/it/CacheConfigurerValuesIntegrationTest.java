package com.exepex.stratum.cache.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.cache.it.configured.ConfiguredProductsApplication;
import com.exepex.stratum.cache.testsupport.ProductCatalog;
import com.exepex.stratum.test.RedisContainerConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisTemplate;

@SpringBootTest(
    classes = ConfiguredProductsApplication.class,
    properties = "spring.application.name=" + CacheConfigurerValuesIntegrationTest.SERVICE)
@Import(RedisContainerConfiguration.class)
@DisplayName("A service with its own CachingConfigurer keeps Java-serialized values on Redis")
class CacheConfigurerValuesIntegrationTest {

  /** The service's name, which its Redis cache keys start with. */
  static final String SERVICE = "configured-service";

  /** The product whose price the test caches. */
  private static final String SKU = "sku-configured";

  /** The Redis key of the product's price. */
  private static final byte[] KEY =
      (SERVICE + ":" + ProductCatalog.PRODUCTS + "::" + SKU).getBytes(StandardCharsets.UTF_8);

  /** The first two bytes of every Java-serialized value. */
  private static final byte[] JAVA_SERIALIZATION_MAGIC = {(byte) 0xAC, (byte) 0xED};

  /** How long the test waits for Spring Data Redis to write in the background. */
  private static final Duration BACKGROUND_WAIT = Duration.ofSeconds(5);

  @Autowired private ProductCatalog catalog;

  @Autowired private RedisTemplate<Object, Object> redisTemplate;

  @Test
  @DisplayName("the value is stored Java-serialized, not as JSON, and its hit returns it")
  void keepsJavaValues() {
    var price = catalog.priceOf(SKU);
    await().atMost(BACKGROUND_WAIT).until(() -> redisTemplate.execute(this::stored, true) != null);
    var lookups = catalog.lookups();

    assertThat(catalog.priceOf(SKU)).isEqualTo(price);
    assertThat(catalog.lookups()).isEqualTo(lookups);
    assertThat(redisTemplate.execute(this::stored, true)).startsWith(JAVA_SERIALIZATION_MAGIC);
  }

  /** Returns the stored bytes of the product's price, or {@code null} before the write. */
  private byte @Nullable [] stored(RedisConnection connection) {
    return connection.stringCommands().get(KEY);
  }
}
