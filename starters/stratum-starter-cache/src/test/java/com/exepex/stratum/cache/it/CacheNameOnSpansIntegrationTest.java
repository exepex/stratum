package com.exepex.stratum.cache.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.cache.CacheConstants;
import com.exepex.stratum.cache.it.products.ProductsApplication;
import com.exepex.stratum.cache.testsupport.ProductCatalog;
import com.exepex.stratum.test.RedisContainerConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest(
    classes = {ProductsApplication.class, CacheNameOnSpansIntegrationTest.Spans.class},
    properties = {
      "spring.application.name=" + CacheNameOnSpansIntegrationTest.SERVICE,
      "spring.cache.cache-names=" + ProductCatalog.QUOTES,
      "management.tracing.sampling.probability=1.0"
    })
@Import(RedisContainerConfiguration.class)
@DisplayName(
    "A traced service's Redis cache commands carry the cache name on their spans, not as tags")
class CacheNameOnSpansIntegrationTest {

  /** The service's name, which its Redis cache keys start with. */
  static final String SERVICE = "spans-service";

  /** The product whose price the span test caches; its text must never reach a span. */
  private static final String SKU = "sku-traced-1";

  /** The product whose price the timer test caches, so each test makes its own Redis commands. */
  private static final String TIMED_SKU = "sku-timed-1";

  /** How long a test waits for Lettuce to end a span. */
  private static final Duration SPAN_WAIT = Duration.ofSeconds(5);

  /** The span attribute Stratum adds. */
  private static final AttributeKey<String> CACHE_NAME =
      AttributeKey.stringKey(CacheConstants.SPAN_CACHE_NAME);

  /** The timer Spring Boot records for each Lettuce command. */
  private static final String LETTUCE_TIMER = "lettuce";

  /** The spans that ended, kept in memory. */
  private static final InMemorySpanExporter EXPORTER = InMemorySpanExporter.create();

  @Autowired private ProductCatalog catalog;

  @Autowired private CacheManager cacheManager;

  @Autowired private StringRedisTemplate redis;

  @Autowired private MeterRegistry meterRegistry;

  /** Forgets the spans of earlier tests. */
  @BeforeEach
  void forgetSpans() {
    EXPORTER.reset();
  }

  /** Waits until a span of the Redis command ends, and returns the Redis spans ended so far. */
  private List<SpanData> redisSpansAfter(String command) {
    return await()
        .atMost(SPAN_WAIT)
        .until(this::redisSpans, spans -> spans.stream().anyMatch(span -> isNamed(span, command)));
  }

  /** Returns the ended spans of Redis commands. */
  private List<SpanData> redisSpans() {
    return EXPORTER.getFinishedSpanItems().stream()
        .filter(
            span -> "redis".equals(span.getAttributes().get(AttributeKey.stringKey("db.system"))))
        .toList();
  }

  /** Returns whether a span is that of the Redis command. */
  private boolean isNamed(SpanData span, String command) {
    return span.getName().equals(command);
  }

  /** Returns the cache names on the spans. */
  private List<String> cacheNamesOn(List<SpanData> spans) {
    return spans.stream()
        .map(span -> span.getAttributes().get(CACHE_NAME))
        .filter(Objects::nonNull)
        .toList();
  }

  @Test
  @DisplayName("a cached call's GET and SET carry cache.name=products and no key text")
  void cachedCallIsNamed() {
    catalog.priceOf(SKU);

    var spans = redisSpansAfter("set");

    assertThat(cacheNamesOn(spans)).isNotEmpty().containsOnly(ProductCatalog.PRODUCTS);
    assertThat(spans)
        .allSatisfy(span -> assertThat(span.getAttributes().toString()).doesNotContain(SKU));
  }

  @Test
  @DisplayName("a cache in spring.cache.cache-names is named even through the cache manager itself")
  void configuredCacheIsNamed() {
    Objects.requireNonNull(cacheManager.getCache(ProductCatalog.QUOTES)).evictIfPresent("EUR");

    assertThat(cacheNamesOn(redisSpansAfter("del"))).containsOnly(ProductCatalog.QUOTES);
  }

  @Test
  @DisplayName("a key of the service's own, even shaped like a cache key, carries no cache name")
  void ownKeyIsNotNamed() {
    redis.opsForValue().set(SERVICE + ":orders::42", "lines");

    assertThat(cacheNamesOn(redisSpansAfter("set"))).isEmpty();
  }

  @Test
  @DisplayName("Spring Boot's lettuce timer gets no cache.name tag, so its series stay as they are")
  void timerHasNoCacheNameTag() {
    catalog.priceOf(TIMED_SKU);
    redisSpansAfter("get");

    assertThat(meterRegistry.find(LETTUCE_TIMER).timers())
        .isNotEmpty()
        .allSatisfy(
            timer -> assertThat(timer.getId().getTag(CacheConstants.SPAN_CACHE_NAME)).isNull());
  }

  /** Ends each span into the in-memory exporter at once; Spring Boot adds it to OpenTelemetry. */
  @TestConfiguration(proxyBeanMethods = false)
  static class Spans {

    /**
     * Returns the processor that exports each span as it ends.
     *
     * @return the processor
     */
    @Bean
    SpanProcessor inMemorySpans() {
      return SimpleSpanProcessor.create(EXPORTER);
    }
  }
}
