package com.exepex.stratum.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.cache.testsupport.AdjustableClock;
import com.exepex.stratum.cache.testsupport.ProductCatalog;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WarningThrottle admits one warning per cache name per interval, within its bound")
class WarningThrottleTest {

  /** The interval the tests throttle by. */
  private static final Duration INTERVAL = Duration.ofMinutes(1);

  /** The cap the throttle in these tests is built with. */
  private static final int MAX_CACHES = 3;

  /** When the tests start. */
  private static final Instant START = Instant.parse("2026-11-04T08:00:00Z");

  private final AdjustableClock clock = new AdjustableClock(START);

  private final WarningThrottle throttle = new WarningThrottle(INTERVAL, clock, MAX_CACHES);

  @Test
  @DisplayName("a name warns once per interval, and again after it")
  void warnsOncePerInterval() {
    assertThat(throttle.shouldWarn(ProductCatalog.PRODUCTS)).isTrue();
    assertThat(throttle.shouldWarn(ProductCatalog.PRODUCTS)).isFalse();

    clock.advance(INTERVAL.minusMillis(1));
    assertThat(throttle.shouldWarn(ProductCatalog.PRODUCTS)).isFalse();

    clock.advance(Duration.ofMillis(1));
    assertThat(throttle.shouldWarn(ProductCatalog.PRODUCTS)).isTrue();
  }

  @Test
  @DisplayName("names warn independently of each other")
  void namesAreIndependent() {
    assertThat(throttle.shouldWarn(ProductCatalog.PRODUCTS)).isTrue();

    assertThat(throttle.shouldWarn("orders")).isTrue();
    assertThat(throttle.shouldWarn(ProductCatalog.PRODUCTS)).isFalse();
    assertThat(throttle.shouldWarn("orders")).isFalse();
  }

  @Test
  @DisplayName("a name past the cap shares the over-limit slot, one warning per interval")
  void overflowSharesOneSlot() {
    for (var name = 0; name < MAX_CACHES; name++) {
      assertThat(throttle.shouldWarn("cache-" + name)).isTrue();
    }

    // Both names past the cap share the one slot, so only the first warns this interval.
    assertThat(throttle.shouldWarn("extra-1")).isTrue();
    assertThat(throttle.shouldWarn("extra-2")).isFalse();

    clock.advance(INTERVAL);
    assertThat(throttle.shouldWarn("extra-2")).isTrue();
    assertThat(throttle.shouldWarn("extra-1")).isFalse();
  }

  @Test
  @DisplayName("counting a name never spends its warning")
  void countingKeepsTheWarning() {
    assertThat(throttle.tagValueFor(ProductCatalog.PRODUCTS)).isEqualTo(ProductCatalog.PRODUCTS);
    assertThat(throttle.tagValueFor(ProductCatalog.PRODUCTS)).isEqualTo(ProductCatalog.PRODUCTS);

    assertThat(throttle.shouldWarn(ProductCatalog.PRODUCTS)).isTrue();
  }

  @Test
  @DisplayName("counting folds names past the cap into the over-limit tag")
  void countingFoldsOverflow() {
    for (var name = 0; name < MAX_CACHES; name++) {
      throttle.tagValueFor("cache-" + name);
    }

    assertThat(throttle.tagValueFor("extra-1")).isEqualTo(CacheConstants.OVER_LIMIT);
    assertThat(throttle.tagValueFor("extra-2")).isEqualTo(CacheConstants.OVER_LIMIT);
  }
}
