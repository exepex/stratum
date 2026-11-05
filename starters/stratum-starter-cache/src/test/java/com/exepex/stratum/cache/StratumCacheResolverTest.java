package com.exepex.stratum.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.cache.testsupport.AdjustableClock;
import com.exepex.stratum.cache.testsupport.RecordingCacheCounters;
import com.exepex.stratum.cache.testsupport.RecordingCacheManager;
import com.exepex.stratum.test.LogLineCapture;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.BasicOperation;
import org.springframework.cache.interceptor.CacheOperationInvocationContext;
import org.springframework.cache.support.NoOpCache;

@DisplayName("StratumCacheResolver wraps each admitted cache and refuses names over the cap")
class StratumCacheResolverTest {

  /** The cap in these tests, so the bound shows with a few names. */
  private static final int MAX_CACHES = 100;

  /** The interval warnings are throttled by. */
  private static final Duration INTERVAL = Duration.ofMinutes(1);

  /** When the tests start. */
  private static final Instant START = Instant.parse("2026-11-04T08:00:00Z");

  /** How many names over the cap the tests resolve. */
  private static final int OVER_CAP = 50;

  /** Every name of the cap-and-flood tests. */
  private static final int ALL_NAMES = MAX_CACHES + OVER_CAP;

  /** The flood of names the bound test resolves. */
  private static final int FLOOD = 10_000;

  /** The bean name of the cache manager in these tests. */
  private static final String MANAGER_NAME = "cacheManager";

  /** The name of the one cache the admitted-cache tests resolve. */
  private static final String FIRST_CACHE = "cache-0";

  /** The key the null tests write. */
  private static final String KEY = "sku-1";

  private final AdjustableClock clock = new AdjustableClock(START);

  private final WarningThrottle throttle = new WarningThrottle(INTERVAL, clock, MAX_CACHES);

  private final RecordingCacheCounters counters = new RecordingCacheCounters();

  private final RecordingCacheManager manager = new RecordingCacheManager();

  private final StratumCacheResolver resolver = resolverFor(manager);

  /** Returns a resolver over a cache manager, with the tests' cap, throttle and counters. */
  private StratumCacheResolver resolverFor(CacheManager cacheManager) {
    return new StratumCacheResolver(
        cacheManager,
        MANAGER_NAME,
        MAX_CACHES,
        throttle,
        counters,
        new StratumCacheErrorHandler(null, throttle, counters),
        UnaryOperator.identity(),
        CacheValueTyping.none());
  }

  /** Returns an invocation context whose operation asks for the given cache names. */
  private static CacheOperationInvocationContext<?> invocation(String... names) {
    return new CacheOperationInvocationContext<>() {
      @Override
      public BasicOperation getOperation() {
        return () -> Set.of(names);
      }

      @Override
      public Object getTarget() {
        return this;
      }

      @Override
      public Method getMethod() {
        throw new UnsupportedOperationException();
      }

      @Override
      public Object[] getArgs() {
        return new Object[0];
      }
    };
  }

  /** Resolves one cache per name, {@code cache-0} to {@code cache-(count-1)}. */
  private List<? extends Cache> resolveNames(int count) {
    return IntStream.range(0, count)
        .mapToObj(number -> "cache-" + number)
        .flatMap(name -> resolver.resolveCaches(invocation(name)).stream())
        .toList();
  }

  @Nested
  @DisplayName("with more names than the cap")
  class OverTheCap {

    @Test
    @DisplayName(
        "the first 100 names are wrapped, the 50 beyond share one no-op cache and the"
            + " manager holds exactly 100 caches")
    void capsTheNames() {
      var resolved = resolveNames(ALL_NAMES);

      assertThat(resolved.subList(0, MAX_CACHES))
          .allSatisfy(cache -> assertThat(cache).isInstanceOf(StratumCache.class));
      var refused = resolved.subList(MAX_CACHES, resolved.size());
      assertThat(refused).allSatisfy(cache -> assertThat(cache).isInstanceOf(NoOpCache.class));
      assertThat(refused.stream().distinct()).hasSize(1);
      assertThat(manager.createdCount()).isEqualTo(MAX_CACHES);
      assertThat(counters.skips(CacheConstants.OVER_LIMIT, CacheConstants.REASON_CACHE_LIMIT))
          .isEqualTo(OVER_CAP);
    }

    @Test
    @DisplayName(
        "a refused operation still runs: the collection is never empty, a read misses and"
            + " a load runs the loader once")
    void refusedNameRunsUncached() {
      resolveNames(MAX_CACHES);

      var refused = resolver.resolveCaches(invocation("cache-refused"));

      assertThat(refused).hasSize(1);
      var noOp = refused.iterator().next();
      assertThat(noOp.get("key")).isNull();
      var loads = new AtomicInteger();
      var value =
          noOp.get(
              "key",
              () -> {
                loads.incrementAndGet();
                return "value";
              });
      assertThat(value).isEqualTo("value");
      assertThat(loads).hasValue(1);
      assertThat(manager.created("cache-refused")).isNull();
    }

    @Test
    @DisplayName("the refusals warn once per interval, naming the refused cache")
    void warnsOncePerInterval() {
      var capture = new LogLineCapture(StratumCacheResolver.class.getName());
      capture.start();
      try {
        resolveNames(ALL_NAMES);

        assertThat(capture.lines()).hasSize(1);
        var line = capture.lines().getFirst().getFormattedMessage();
        assertThat(line).contains("SX-CACHE-1003").contains("cache-" + MAX_CACHES);

        clock.advance(INTERVAL);
        resolver.resolveCaches(invocation("cache-" + (ALL_NAMES - 1)));
        assertThat(capture.lines()).hasSize(2);
      } finally {
        capture.stop();
      }
    }
  }

  @Nested
  @DisplayName("under a flood of names")
  class Bounded {

    @Test
    @DisplayName(
        "10,000 names keep the wrapped caches, the manager and every series within the"
            + " cap plus one")
    void tenThousandNamesStayBounded() {
      var resolved = resolveNames(FLOOD);

      assertThat(resolved.stream().filter(StratumCache.class::isInstance)).hasSize(MAX_CACHES);
      assertThat(resolved.stream().filter(NoOpCache.class::isInstance).distinct()).hasSize(1);
      assertThat(manager.createdCount()).isEqualTo(MAX_CACHES);
      assertThat(counters.skipSeries()).isOne();
      assertThat(counters.skips(CacheConstants.OVER_LIMIT, CacheConstants.REASON_CACHE_LIMIT))
          .isEqualTo(FLOOD - MAX_CACHES);
    }
  }

  @Nested
  @DisplayName("for each admitted cache")
  class AdmittedCache {

    /** Returns a Spring in-memory cache manager that stores nulls or refuses them. */
    private ConcurrentMapCacheManager inMemory(boolean allowNullValues) {
      var inMemory = new ConcurrentMapCacheManager();
      inMemory.setAllowNullValues(allowNullValues);
      return inMemory;
    }

    /** Resolves the one cache {@code cache-0} through a resolver over the given manager. */
    private Cache resolvedThrough(CacheManager cacheManager) {
      return resolverFor(cacheManager).resolveCaches(invocation(FIRST_CACHE)).iterator().next();
    }

    @Test
    @DisplayName("the cache the manager made is measured once, never the layer's decorator")
    void measuresTheManagersCache() {
      resolver.resolveCaches(invocation(FIRST_CACHE));
      resolver.resolveCaches(invocation(FIRST_CACHE));

      assertThat(counters.measured()).containsExactly(manager.created(FIRST_CACHE));
    }

    @Test
    @DisplayName("in a cache that stores nulls, a null put stores the null, as the team chose")
    void storesNullWhereAllowed() {
      var inMemory = inMemory(true);

      resolvedThrough(inMemory).put(KEY, null);

      var stored = inMemory.getCache(FIRST_CACHE).get(KEY);
      assertThat(stored).isNotNull();
      assertThat(stored.get()).isNull();
    }

    @Test
    @DisplayName("in a cache that refuses nulls, a null put removes the old entry")
    void evictsWhereRefused() {
      var inMemory = inMemory(false);
      var cache = resolvedThrough(inMemory);
      inMemory.getCache(FIRST_CACHE).put(KEY, "old-price");

      cache.put(KEY, null);

      assertThat(inMemory.getCache(FIRST_CACHE).get(KEY)).isNull();
    }

    @Test
    @DisplayName("in a cache that says nothing about nulls, a null put is passed on unchanged")
    void forwardsWhereUnknown() {
      resolvedThrough(manager).put(KEY, null);

      assertThat(manager.created(FIRST_CACHE).calls()).contains("put").doesNotContain("evict");
    }
  }

  @Nested
  @DisplayName("with the Redis retry gate")
  class RedisGate {

    @Test
    @DisplayName("each admitted cache is passed through the gate before it is wrapped")
    void admittedCacheIsGated() {
      var gatedResolver =
          new StratumCacheResolver(
              manager,
              MANAGER_NAME,
              MAX_CACHES,
              throttle,
              counters,
              new StratumCacheErrorHandler(null, throttle, counters),
              cache -> new NoOpCache("gated-" + cache.getName()),
              CacheValueTyping.none());

      var resolved = gatedResolver.resolveCaches(invocation("products"));

      assertThat(resolved).singleElement().extracting(Cache::getName).isEqualTo("gated-products");
    }
  }

  @Nested
  @DisplayName("with value typing")
  class ValueTyping {

    /** Types only the cache named "typed", renaming it so the test sees the typed view. */
    private final CacheValueTyping typing =
        new CacheValueTyping() {
          @Override
          public boolean appliesTo(Cache cache) {
            return "typed".equals(cache.getName());
          }

          @Override
          public Cache typed(Cache cache, CacheOperationInvocationContext<?> context) {
            return new NoOpCache("view-" + cache.getName());
          }
        };

    @Test
    @DisplayName("a cache the typing applies to is given each call's typed view; others are not")
    void typesOnlyTheCachesItAppliesTo() {
      var typingResolver =
          new StratumCacheResolver(
              manager,
              MANAGER_NAME,
              MAX_CACHES,
              throttle,
              counters,
              new StratumCacheErrorHandler(null, throttle, counters),
              UnaryOperator.identity(),
              typing);

      var resolved = typingResolver.resolveCaches(invocation("typed", "plain"));

      assertThat(resolved)
          .extracting(Cache::getName)
          .containsExactlyInAnyOrder("view-typed", "plain");
    }
  }
}
