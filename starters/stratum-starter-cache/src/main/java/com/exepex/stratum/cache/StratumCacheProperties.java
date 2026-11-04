package com.exepex.stratum.cache;

import com.exepex.stratum.core.properties.StratumCustomizers;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.env.Environment;

/**
 * The settings of the cache module, bound to {@code stratum.cache.*} and then changed by the
 * service's {@link StratumCachePropertiesCustomizer} classes, which win. The descriptions of the
 * components are what IDEs show, so they are plain sentences.
 *
 * @param maxCaches The most caches the module admits. A name beyond it runs uncached through a
 *     shared no-op cache, so no cache is created for it. From 1. Defaults to 100.
 * @param warningInterval How often a cache's warning, such as a failed operation while Redis is
 *     down, is repeated at most. From 1s to 1h. Defaults to 1m.
 */
@Builder(toBuilder = true)
@ConfigurationProperties(prefix = CacheConstants.PREFIX)
public record StratumCacheProperties(
    @DefaultValue("100") @Min(1) int maxCaches, @DefaultValue("1m") Duration warningInterval) {

  /** The shortest allowed {@code warning-interval}. */
  private static final Duration MIN_WARNING_INTERVAL = Duration.ofSeconds(1);

  /** The longest allowed {@code warning-interval}. */
  private static final Duration MAX_WARNING_INTERVAL = Duration.ofHours(1);

  /**
   * Returns the settings the module uses: {@code stratum.cache.*} with the defaults for anything
   * unset, changed by every {@link StratumCachePropertiesCustomizer} in {@code
   * META-INF/spring.factories}, then validated. The module makes its own validator, as every
   * Stratum module does.
   *
   * @param environment the application's environment
   * @return the settings
   * @throws com.exepex.stratum.core.error.StratumException {@code SX-CORE-1003} if a setting breaks
   *     its rules
   */
  public static StratumCacheProperties resolve(Environment environment) {
    return StratumCustomizers.resolveSettings(
        environment,
        CacheConstants.PREFIX,
        StratumCacheProperties.class,
        StratumCachePropertiesCustomizer.class);
  }

  /**
   * Returns whether {@code warning-interval} is from 1s to 1h. Validation reports a value out of
   * that range as {@code stratum.cache.warning-interval-in-range breaks @AssertTrue}.
   *
   * @return whether the interval is in range
   */
  @AssertTrue
  public boolean isWarningIntervalInRange() {
    return warningInterval.compareTo(MIN_WARNING_INTERVAL) >= 0
        && warningInterval.compareTo(MAX_WARNING_INTERVAL) <= 0;
  }
}
