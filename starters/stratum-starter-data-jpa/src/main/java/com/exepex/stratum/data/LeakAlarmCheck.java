package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.StratumException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Stops startup when the service turns on HikariCP's leak alarm with a threshold HikariCP would
 * silently turn off: under 2 seconds, or above the connections' {@code max-lifetime}.
 */
@StratumInternal
public class LeakAlarmCheck {

  /** The lowest threshold HikariCP keeps, in milliseconds. */
  private static final long LOWEST_THRESHOLD = 2000;

  /** HikariCP's own {@code max-lifetime}, in milliseconds: 30 minutes. */
  private static final long HIKARI_MAX_LIFETIME = 1_800_000;

  /**
   * Checks the threshold the service set, in any of Spring Boot's spellings of the key. A threshold
   * of 0, HikariCP's default, means the alarm is off and is not checked; so is a {@code
   * max-lifetime} of 0, which HikariCP reads as no limit.
   *
   * @param environment the application's environment
   * @throws StratumException {@code SX-DATA-1002} if HikariCP would turn the alarm off
   */
  void check(ConfigurableEnvironment environment) {
    var binder = Binder.get(environment);
    long threshold = millisOf(binder, DataConstants.LEAK_DETECTION_THRESHOLD, 0);
    long maxLifetime = millisOf(binder, DataConstants.MAX_LIFETIME, HIKARI_MAX_LIFETIME);
    if (threshold > 0 && threshold < LOWEST_THRESHOLD) {
      throw new StratumException(
          DataErrorCode.LEAK_ALARM_OFF,
          DataConstants.LEAK_DETECTION_THRESHOLD + " is under 2000 milliseconds");
    }
    if (threshold > maxLifetime && maxLifetime > 0) {
      throw new StratumException(
          DataErrorCode.LEAK_ALARM_OFF,
          DataConstants.LEAK_DETECTION_THRESHOLD + " is more than " + DataConstants.MAX_LIFETIME);
    }
  }

  /** Returns the milliseconds the service set for a HikariCP key, or HikariCP's own default. */
  private long millisOf(Binder binder, String key, long hikariDefault) {
    var bound = binder.bind(key, Long.class);
    return bound.isBound() ? bound.get() : hikariDefault;
  }
}
