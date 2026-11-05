package com.exepex.stratum.cache;

import static com.exepex.stratum.cache.testsupport.values.CachedValuesTestData.LOADED_ORDER;
import static com.exepex.stratum.cache.testsupport.values.CachedValuesTestData.ORDER;
import static com.exepex.stratum.cache.testsupport.values.OrderMethods.ORDERS;
import static com.exepex.stratum.cache.testsupport.values.OrderMethods.cacheable;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.cache.testsupport.RecordingCacheCounters;
import com.exepex.stratum.cache.testsupport.values.CachedValuesTestData;
import com.exepex.stratum.cache.testsupport.values.Order;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("TypedCache returns the method's type on every read and loads at most once")
class TypedCacheTest {

  private static final String KEY = "key";

  /** A value that no longer fits the order: its quantity is a list. */
  private static final CachedJson CHANGED =
      CachedValuesTestData.stored("{\"id\":\"order-1\",\"quantity\":[\"a\"]}");

  private final CacheJsonValues json = CachedValuesTestData.jsonValues();

  /** The stored values, as Redis hands them back: unparsed JSON. */
  private final Cache store = new ConcurrentMapCache(ORDERS);

  private final AtomicInteger loads = new AtomicInteger();

  private final Cache orders = typedValues().typed(store, cacheable("order"));

  /** Returns typed values with the default settings and no-op warnings. */
  private TypedCacheValues typedValues() {
    var properties = StratumCacheProperties.resolve(new MockEnvironment());
    var throttle = new WarningThrottle(Duration.ofMinutes(1), Clock.systemUTC(), 1);
    return new TypedCacheValues(json, properties, throttle, throttle, new RecordingCacheCounters());
  }

  /** Loads a fresh order and counts the load. */
  private Order load() {
    loads.incrementAndGet();
    return LOADED_ORDER;
  }

  /** Loads a fresh order in the background and counts the load. */
  private CompletableFuture<Order> loadLater() {
    return CompletableFuture.completedFuture(load());
  }

  @Nested
  @DisplayName("with a loading read")
  class LoadingRead {

    @Test
    @DisplayName("a stored value is read as the method's type without a load")
    void readsStoredValue() {
      store.put(KEY, json.read(json.write(ORDER)));

      assertThat(orders.get(KEY, TypedCacheTest.this::load)).isEqualTo(ORDER);
      assertThat(loads).hasValue(0);
    }

    @Test
    @DisplayName("a miss returns the loaded object itself, unparsed")
    void returnsLoadedObject() {
      assertThat(orders.get(KEY, () -> LOADED_ORDER)).isSameAs(LOADED_ORDER);
    }

    @Test
    @DisplayName("an unreadable value is removed and replaced, loading once")
    void replacesUnreadableValue() {
      store.put(KEY, CHANGED);

      assertThat(orders.get(KEY, TypedCacheTest.this::load)).isEqualTo(LOADED_ORDER);
      assertThat(loads).hasValue(1);
      assertThat(store.get(KEY))
          .isNotNull()
          .extracting(Cache.ValueWrapper::get)
          .isEqualTo(LOADED_ORDER);
    }
  }

  @Nested
  @DisplayName("with a loading read, over a cache that sends evicts later, as Redis may")
  class LateEvicts {

    /** The stored values, with an evict that never arrives in time, as Spring Data Redis allows. */
    private final Cache lateEvictStore =
        new ConcurrentMapCache(ORDERS) {
          @Override
          public void evict(Object key) {
            // Sent later: the reload reads the old value.
          }
        };

    @Test
    @DisplayName("an unreadable value is removed at once, so the reload stores the loaded value")
    void removesUnreadableValueAtOnce() {
      lateEvictStore.put(KEY, CHANGED);

      var lateOrders = typedValues().typed(lateEvictStore, cacheable("order"));

      assertThat(lateOrders.get(KEY, TypedCacheTest.this::load)).isEqualTo(LOADED_ORDER);
      assertThat(loads).hasValue(1);
      assertThat(lateEvictStore.get(KEY))
          .isNotNull()
          .extracting(Cache.ValueWrapper::get)
          .isEqualTo(LOADED_ORDER);
    }
  }

  @Nested
  @DisplayName("with a stored null, in a cache that keeps nulls")
  class StoredNulls {

    @Test
    @DisplayName("a plain read is a hit that holds null")
    void plainReadHits() {
      store.put(KEY, null);

      assertThat(orders.get(KEY)).isNotNull().extracting(Cache.ValueWrapper::get).isNull();
    }

    @Test
    @DisplayName("a loading read returns null without a load")
    void loadingReadHits() {
      store.put(KEY, null);

      assertThat(orders.get(KEY, TypedCacheTest.this::load)).isNull();
      assertThat(loads).hasValue(0);
    }

    @Test
    @DisplayName("a background loading read returns null without a load")
    void backgroundReadHits() {
      store.put(KEY, null);

      assertThat(orders.retrieve(KEY, TypedCacheTest.this::loadLater).join()).isNull();
      assertThat(loads).hasValue(0);
    }
  }

  @Nested
  @DisplayName("with a plain read")
  class PlainRead {

    @Test
    @DisplayName("an unreadable value is a miss, so the method runs and its put replaces it")
    void unreadableMisses() {
      store.put(KEY, CHANGED);

      assertThat(orders.get(KEY)).isNull();
      assertThat(orders.get(KEY, Order.class)).isNull();
    }

    @Test
    @DisplayName("put if absent returns the stored value as the method's type")
    void putIfAbsentReturnsTyped() {
      store.put(KEY, json.read(json.write(ORDER)));

      var existing = orders.putIfAbsent(KEY, LOADED_ORDER);

      assertThat(existing).isNotNull().extracting(Cache.ValueWrapper::get).isEqualTo(ORDER);
    }
  }

  @Nested
  @DisplayName("with a background read")
  class BackgroundRead {

    @Test
    @DisplayName("a stored value is read as the method's type")
    void retrievesTyped() {
      store.put(KEY, json.read(json.write(ORDER)));

      var retrieved = orders.retrieve(KEY);

      assertThat(retrieved).isNotNull();
      assertThat(retrieved.join())
          .isInstanceOf(Cache.ValueWrapper.class)
          .extracting(wrapper -> ((Cache.ValueWrapper) wrapper).get())
          .isEqualTo(ORDER);
    }

    @Test
    @DisplayName("a loading read of an unreadable value loads once and stores the result")
    void replacesUnreadableValue() {
      store.put(KEY, CHANGED);

      var value = orders.retrieve(KEY, TypedCacheTest.this::loadLater).join();

      assertThat(value).isEqualTo(LOADED_ORDER);
      assertThat(loads).hasValue(1);
      assertThat(store.get(KEY))
          .isNotNull()
          .extracting(Cache.ValueWrapper::get)
          .isEqualTo(LOADED_ORDER);
    }

    @Test
    @DisplayName("a loading read of an unreadable value that loads null stores the null")
    void storesLoadedNull() {
      store.put(KEY, CHANGED);

      var value = orders.retrieve(KEY, () -> CompletableFuture.completedFuture(null)).join();

      assertThat(value).isNull();
      assertThat(store.get(KEY)).isNotNull().extracting(Cache.ValueWrapper::get).isNull();
    }

    @Test
    @DisplayName("a loading read of a stored value returns it typed without a load")
    void readsStoredValue() {
      store.put(KEY, json.read(json.write(ORDER)));

      assertThat(orders.retrieve(KEY, TypedCacheTest.this::loadLater).join()).isEqualTo(ORDER);
      assertThat(loads).hasValue(0);
    }
  }
}
