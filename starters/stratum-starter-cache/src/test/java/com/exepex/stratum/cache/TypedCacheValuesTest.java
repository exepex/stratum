package com.exepex.stratum.cache;

import static com.exepex.stratum.cache.testsupport.values.CachedValuesTestData.ORDER;
import static com.exepex.stratum.cache.testsupport.values.OrderMethods.ORDERS;
import static com.exepex.stratum.cache.testsupport.values.OrderMethods.cachePut;
import static com.exepex.stratum.cache.testsupport.values.OrderMethods.cacheable;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.cache.testsupport.AdjustableClock;
import com.exepex.stratum.cache.testsupport.RecordingCacheCounters;
import com.exepex.stratum.cache.testsupport.values.CachedValuesTestData;
import com.exepex.stratum.test.LogLineCapture;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.NoOpCache;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("TypedCacheValues reads each cached method's values as its return type")
class TypedCacheValuesTest {

  /** How often a warning is repeated at most. */
  private static final Duration INTERVAL = Duration.ofMinutes(1);

  /** The key of every stored value. */
  private static final String KEY = "key";

  /** The cached method that returns an order. */
  private static final String ORDER_METHOD = "order";

  /** The cached method that returns a type from a package that is not allowed. */
  private static final String GADGET_METHOD = "gadget";

  private final AdjustableClock clock = new AdjustableClock(Instant.parse("2026-11-05T08:00:00Z"));

  private final RecordingCacheCounters counters = new RecordingCacheCounters();

  private final CacheJsonValues json = CachedValuesTestData.jsonValues();

  /** The stored values, as Redis hands them back: unparsed JSON. */
  private final Cache store = new ConcurrentMapCache(ORDERS);

  private final TypedCacheValues values =
      valuesWith(new MockEnvironment().withProperty(CacheConstants.WARNING_INTERVAL, "1m"));

  /** Returns typed values over the test's JSON, counters and clock, with the given settings. */
  private TypedCacheValues valuesWith(MockEnvironment environment) {
    var properties = StratumCacheProperties.resolve(environment);
    return new TypedCacheValues(
        json,
        properties,
        new WarningThrottle(INTERVAL, clock, properties.maxCaches()),
        new WarningThrottle(INTERVAL, clock, properties.maxCaches()),
        counters);
  }

  /** Stores a value as JSON, as the Redis cache writes it. */
  private void storeAsJson(Object value) {
    store.put(KEY, json.read(json.write(value)));
  }

  @Nested
  @DisplayName("deciding a method's type")
  class ValueTypes {

    /** Methods whose declared type can be read: concrete, from java or the allowed package. */
    private static final List<String> READABLE =
        List.of(
            ORDER_METHOD,
            "times",
            "ids",
            "ordersById",
            "orderArray",
            "count",
            "quantity",
            "maybeOrder",
            "orderLater",
            "orderMono",
            "orderFlux",
            "paidOrder");

    /** Methods whose type is refused, with the reason counted. */
    private static final List<Arguments> REFUSED =
        List.of(
            Arguments.of("anything", CacheConstants.REASON_TYPE_NOT_CONCRETE),
            Arguments.of("payment", CacheConstants.REASON_TYPE_NOT_CONCRETE),
            Arguments.of("report", CacheConstants.REASON_TYPE_NOT_CONCRETE),
            Arguments.of("payments", CacheConstants.REASON_TYPE_NOT_CONCRETE),
            Arguments.of(GADGET_METHOD, CacheConstants.REASON_TYPE_NOT_ALLOWED),
            Arguments.of("gadgets", CacheConstants.REASON_TYPE_NOT_ALLOWED));

    @ParameterizedTest(name = "{0}")
    @FieldSource("READABLE")
    @DisplayName("a concrete type from java or an allowed package gets a typed view")
    void readableTypeIsTyped(String methodName) {
      assertThat(values.typed(store, cacheable(methodName))).isInstanceOf(TypedCache.class);
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("REFUSED")
    @DisplayName("a refused type runs without the cache and is counted with its reason")
    void refusedTypeRunsUncached(String methodName, String reason) {
      var cache = values.typed(store, cacheable(methodName));

      assertThat(cache).isInstanceOf(NoOpCache.class);
      assertThat(cache.getName()).isEqualTo(ORDERS);
      assertThat(counters.skips(ORDERS, reason)).isOne();
    }

    @Test
    @DisplayName("a put needs no type and gets the cache unchanged, even for a refused type")
    void putPassesThrough() {
      assertThat(values.typed(store, cachePut("anything"))).isSameAs(store);
    }

    @Test
    @DisplayName(
        "a refusal warns once per interval with SX-CACHE-1004, naming the type and setting")
    void refusalWarnsOncePerInterval() {
      var capture = new LogLineCapture(TypedCacheValues.class.getName());
      capture.start();
      try {
        values.typed(store, cacheable(GADGET_METHOD));
        values.typed(store, cacheable(GADGET_METHOD));

        assertThat(capture.lines()).hasSize(1);
        assertThat(capture.lines().getFirst().getFormattedMessage())
            .contains("SX-CACHE-1004", "Gadget", CacheConstants.ALLOWED_PACKAGES)
            .doesNotContain(KEY);

        clock.advance(INTERVAL);
        values.typed(store, cacheable(GADGET_METHOD));
        assertThat(capture.lines()).hasSize(2);
      } finally {
        capture.stop();
      }
    }
  }

  @Nested
  @DisplayName("reading stored JSON")
  class Reading {

    /** Each method with a value of its stored type, which must read back equal. */
    private static final Supplier<Stream<Arguments>> ROUND_TRIPS =
        () ->
            Stream.of(
                Arguments.of(ORDER_METHOD, ORDER),
                Arguments.of(
                    "times", List.of(Instant.EPOCH, Instant.parse("2026-11-05T08:00:00Z"))),
                Arguments.of("ids", Set.of(new UUID(1, 2))),
                Arguments.of("ordersById", Map.of("order-1", ORDER)),
                Arguments.of("count", CachedValuesTestData.COUNT),
                Arguments.of("quantity", CachedValuesTestData.QUANTITY),
                Arguments.of("maybeOrder", ORDER),
                Arguments.of("orderLater", ORDER),
                Arguments.of("orderMono", ORDER),
                Arguments.of("orderFlux", List.of(ORDER)),
                Arguments.of("paidOrder", CachedValuesTestData.PAID_ORDER));

    @ParameterizedTest(name = "{0}")
    @FieldSource("ROUND_TRIPS")
    @DisplayName("each shape reads back as the method's type, type arguments included")
    void roundTrips(String methodName, Object value) {
      storeAsJson(value);

      var read = values.typed(store, cacheable(methodName)).get(KEY);

      assertThat(read).isNotNull();
      assertThat(read.get()).isEqualTo(value);
    }

    @Test
    @DisplayName("an array of orders reads back as an array")
    void readsArrays() {
      storeAsJson(new Object[] {ORDER});

      var read = values.typed(store, cacheable("orderArray")).get(KEY);

      assertThat(read).isNotNull();
      assertThat(read.get())
          .isInstanceOf(Object[].class)
          .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.ARRAY)
          .containsExactly(ORDER);
    }

    @Test
    @DisplayName("a field added since the value was stored reads as its default, 0 for an int")
    void addedFieldTakesItsDefault() {
      storeAsJson(ORDER);

      var read = values.typed(store, cacheable("orderWithPriority")).get(KEY);

      assertThat(read).isNotNull();
      assertThat(read.get()).isEqualTo(CachedValuesTestData.ORDER_READ_LATER);
    }

    @Test
    @DisplayName("a field removed since the value was stored is ignored")
    void unknownFieldIsIgnored() {
      storeAsJson(CachedValuesTestData.ORDER_WITH_PRIORITY);

      var read = values.typed(store, cacheable(ORDER_METHOD)).get(KEY);

      assertThat(read).isNotNull();
      assertThat(read.get()).isEqualTo(ORDER);
    }
  }

  @Nested
  @DisplayName("with a value that cannot be read")
  class Unreadable {

    @Test
    @DisplayName("a field whose type changed is a miss, counted and warned once with SX-CACHE-1005")
    void changedFieldTypeMisses() {
      var capture = new LogLineCapture(TypedCacheValues.class.getName());
      capture.start();
      try {
        storeAsJson(ORDER);
        var cache = values.typed(store, cacheable("orderWithItemList"));

        assertThat(cache.get(KEY)).isNull();
        assertThat(cache.get(KEY)).isNull();

        assertThat(counters.skips(ORDERS, CacheConstants.REASON_UNREADABLE)).isEqualTo(2);
        assertThat(capture.lines())
            .singleElement()
            .extracting(line -> line.getFormattedMessage())
            .asString()
            .contains("SX-CACHE-1005", "OrderWithItemList")
            .doesNotContain("order-1");
      } finally {
        capture.stop();
      }
    }

    @Test
    @DisplayName("a value over the cache's size limit is a miss and is never parsed")
    void oversizedValueIsNeverParsed() {
      var limited =
          valuesWith(
              new MockEnvironment()
                  .withProperty(
                      CacheConstants.PREFIX + ".caches." + ORDERS + ".max-value-size", "16B"));
      storeAsJson(ORDER);

      assertThat(limited.typed(store, cacheable(ORDER_METHOD)).get(KEY)).isNull();
      assertThat(counters.skips(ORDERS, CacheConstants.REASON_UNREADABLE)).isOne();
    }

    @Test
    @DisplayName("JSON nested past Jackson's depth limit is a miss")
    void deepNestingMisses() {
      store.put(KEY, CachedValuesTestData.nestedPastDepthLimit());

      assertThat(values.typed(store, cacheable("orderFlux")).get(KEY)).isNull();
    }

    @Test
    @DisplayName("a JSON null is a miss")
    void jsonNullMisses() {
      store.put(KEY, CachedValuesTestData.stored("null"));

      assertThat(values.typed(store, cacheable(ORDER_METHOD)).get(KEY)).isNull();
    }
  }

  @Nested
  @DisplayName("with a class named inside the JSON")
  class NamedClasses {

    @Test
    @DisplayName(
        "a class from a package that only starts like an allowed one is refused, never loaded")
    void gadgetIsNeverLoaded() {
      store.put(KEY, CachedValuesTestData.paidWithGadget());

      assertThat(values.typed(store, cacheable("paidOrder")).get(KEY)).isNull();
      assertThat(CachedValuesTestData.GADGET_LOADED).isFalse();
    }

    @Test
    @DisplayName("a java class named inside the JSON is refused, though a method may declare one")
    void javaClassNamedInJsonIsRefused() {
      storeAsJson(CachedValuesTestData.MEASURED_ORDER);

      assertThat(values.typed(store, cacheable("measuredOrder")).get(KEY)).isNull();
    }
  }
}
