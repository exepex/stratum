package com.exepex.stratum.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.exepex.stratum.cache.testsupport.AdjustableClock;
import com.exepex.stratum.cache.testsupport.RecordingCacheCounters;
import com.exepex.stratum.cache.testsupport.RecordingCacheErrorHandler;
import com.exepex.stratum.cache.testsupport.StubCache;
import com.exepex.stratum.test.LogLineCapture;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

@DisplayName("StratumCacheErrorHandler counts and warns throttled, then delegates or swallows")
class StratumCacheErrorHandlerTest {

  /** The interval warnings are throttled by. */
  private static final Duration INTERVAL = Duration.ofMinutes(1);

  /** The cap the throttle in these tests is built with. */
  private static final int MAX_CACHES = 100;

  /** How many failures the throttling test makes. */
  private static final int FAILURES = 100;

  /** The flood of names the bound test reports. */
  private static final int FLOOD = 10_000;

  /** When the tests start. */
  private static final Instant START = Instant.parse("2026-11-04T08:00:00Z");

  @Nested
  @DisplayName("for each operation")
  class PerOperation {

    /** Each operation, as its tag value and as a call on the handler. */
    private static final List<Arguments> OPERATIONS =
        List.of(
            arguments(
                "get",
                (BiConsumer<CacheErrorHandler, Cache>)
                    (handler, cache) ->
                        handler.handleCacheGetError(StubCache.FAILURE, cache, "key")),
            arguments(
                "put",
                (BiConsumer<CacheErrorHandler, Cache>)
                    (handler, cache) ->
                        handler.handleCachePutError(StubCache.FAILURE, cache, "key", "value")),
            arguments(
                "evict",
                (BiConsumer<CacheErrorHandler, Cache>)
                    (handler, cache) ->
                        handler.handleCacheEvictError(StubCache.FAILURE, cache, "key")),
            arguments(
                "clear",
                (BiConsumer<CacheErrorHandler, Cache>)
                    (handler, cache) -> handler.handleCacheClearError(StubCache.FAILURE, cache)));

    @ParameterizedTest(name = "{0}")
    @FieldSource("OPERATIONS")
    @DisplayName("the failure is counted with its tag, then swallowed without a service handler")
    void countsEachOperation(String operation, BiConsumer<CacheErrorHandler, Cache> call) {
      call.accept(handler, cache);

      assertThat(counters.failures("products", operation)).isOne();
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("OPERATIONS")
    @DisplayName("a service's handler sees every error exactly once")
    void delegatesEachError(String operation, BiConsumer<CacheErrorHandler, Cache> call) {
      var serviceHandler = new RecordingCacheErrorHandler();
      var wrapping = new StratumCacheErrorHandler(serviceHandler, throttle, counters);

      call.accept(wrapping, cache);

      assertThat(serviceHandler.calls()).containsExactly(operation + ":products");
    }
  }

  private final AdjustableClock clock = new AdjustableClock(START);

  private final WarningThrottle throttle = new WarningThrottle(INTERVAL, clock, MAX_CACHES);

  private final RecordingCacheCounters counters = new RecordingCacheCounters();

  private final StubCache cache = new StubCache("products");

  private final StratumCacheErrorHandler handler =
      new StratumCacheErrorHandler(null, throttle, counters);

  @Nested
  @DisplayName("over a flood of failures")
  class Throttling {

    @Test
    @DisplayName("one warning per cache per interval is written, and every failure is counted")
    void warnsOncePerInterval() {
      var capture = new LogLineCapture(StratumCacheErrorHandler.class.getName());
      capture.start();
      try {
        IntStream.range(0, FAILURES)
            .forEach(call -> handler.handleCacheGetError(StubCache.FAILURE, cache, "key"));

        assertThat(counters.failures("products", "get")).isEqualTo(FAILURES);
        assertThat(capture.lines()).hasSize(1);
        assertThat(capture.lines().getFirst().getFormattedMessage())
            .contains("SX-CACHE-1002")
            .contains("products");

        clock.advance(INTERVAL);
        handler.handleCacheGetError(StubCache.FAILURE, cache, "key");
        assertThat(capture.lines()).hasSize(2);
      } finally {
        capture.stop();
      }
    }

    @Test
    @DisplayName("a hostile key, with line breaks or shaped like an email address, is never logged")
    void neverLogsTheKey() {
      var hostileKey = "user@example.com\r\nSX-CACHE-9999 forged line";
      var capture = new LogLineCapture(StratumCacheErrorHandler.class.getName());
      capture.start();
      try {
        handler.handleCacheGetError(StubCache.FAILURE, cache, hostileKey);
      } finally {
        capture.stop();
      }

      var line = capture.onlyLine().getFormattedMessage();
      assertThat(line).contains("SX-CACHE-1002").contains("products");
      assertThat(line).doesNotContain("user@example.com").doesNotContain("forged");
    }

    @Test
    @DisplayName("10,000 failing names keep the failure series within the cap plus one")
    void tenThousandNamesShareTheOverLimitSeries() {
      IntStream.range(0, FLOOD)
          .forEach(
              number ->
                  handler.handleCacheGetError(
                      StubCache.FAILURE, new StubCache("cache-" + number), "key"));

      assertThat(counters.failureSeries()).isLessThanOrEqualTo(MAX_CACHES + 1);
      assertThat(counters.failures(CacheConstants.OVER_LIMIT, "get")).isEqualTo(FLOOD - MAX_CACHES);
    }

    @Test
    @DisplayName("without a service handler the error is swallowed")
    void swallowsWithoutServiceHandler() {
      assertThatCode(() -> handler.handleCacheGetError(StubCache.FAILURE, cache, "key"))
          .doesNotThrowAnyException();
    }
  }
}
