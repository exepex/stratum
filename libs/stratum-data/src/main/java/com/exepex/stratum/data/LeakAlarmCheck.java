package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.StratumException;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Stops startup when the service turns on HikariCP's leak alarm with a threshold HikariCP would
 * silently turn off: under 2 seconds, or above the connections' {@code max-lifetime}. It checks the
 * main pool's {@code spring.datasource.hikari.*} keys and each named pool's {@code
 * stratum.data.sources.<name>.hikari.*} keys.
 */
@StratumInternal
public class LeakAlarmCheck {

  /** The lowest threshold HikariCP keeps, in milliseconds. */
  private static final long LOWEST_THRESHOLD = 2000;

  /** HikariCP's own {@code max-lifetime}, in milliseconds: 30 minutes. */
  private static final long HIKARI_MAX_LIFETIME = 1_800_000;

  /** The named pools' keys, by pool name, only to find the names. */
  private static final Bindable<Map<String, PoolSettings>> POOLS =
      Bindable.mapOf(String.class, PoolSettings.class);

  /** The key of a named pool's leak alarm threshold, after its HikariCP prefix. */
  private static final String THRESHOLD = "leak-detection-threshold";

  /** The key of a named pool's connection lifetime, after its HikariCP prefix. */
  private static final String LIFETIME = "max-lifetime";

  /**
   * Checks the threshold the service set for each pool, in any of Spring Boot's spellings of the
   * key. A threshold of 0, HikariCP's default, means the alarm is off and is not checked; so is a
   * {@code max-lifetime} of 0, which HikariCP reads as no limit.
   *
   * @param environment the application's environment
   * @throws StratumException {@code SX-DATA-1002} if HikariCP would turn the alarm off
   */
  void check(ConfigurableEnvironment environment) {
    var binder = Binder.get(environment);
    checkPool(binder, DataConstants.LEAK_DETECTION_THRESHOLD, DataConstants.MAX_LIFETIME);
    for (var name : binder.bind(DataConstants.SOURCES, POOLS).orElseGet(Map::of).keySet()) {
      var hikari = DataConstants.SOURCES + "." + name + ".hikari.";
      checkPool(binder, hikari + THRESHOLD, hikari + LIFETIME);
    }
  }

  /** Checks one pool's threshold against its lifetime. */
  private void checkPool(Binder binder, String thresholdKey, String lifetimeKey) {
    long threshold = millisOf(binder, thresholdKey, 0);
    long maxLifetime = millisOf(binder, lifetimeKey, HIKARI_MAX_LIFETIME);
    if (threshold > 0 && threshold < LOWEST_THRESHOLD) {
      throw new StratumException(
          DataErrorCode.LEAK_ALARM_OFF, thresholdKey + " is under 2000 milliseconds");
    }
    if (threshold > maxLifetime && maxLifetime > 0) {
      throw new StratumException(
          DataErrorCode.LEAK_ALARM_OFF, thresholdKey + " is more than " + lifetimeKey);
    }
  }

  /** Returns the milliseconds the service set for a HikariCP key, or HikariCP's own default. */
  private long millisOf(Binder binder, String key, long hikariDefault) {
    var bound = binder.bind(key, Long.class);
    return bound.isBound() ? bound.get() : hikariDefault;
  }
}
