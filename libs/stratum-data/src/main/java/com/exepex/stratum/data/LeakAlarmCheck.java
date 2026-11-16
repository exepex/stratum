package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.StratumException;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.Environment;

/**
 * Stops startup when the service turns on HikariCP's leak alarm with a threshold HikariCP would
 * silently turn off: under 2 seconds, or above the connections' {@code max-lifetime}. It checks the
 * main pool's {@code spring.datasource.hikari.*} keys, and each named pool's HikariCP settings as
 * the pool is built from them: its {@code stratum.data.sources.<name>.hikari.*} keys, with the
 * settings of a Java customizer winning over them.
 */
@StratumInternal
public class LeakAlarmCheck {

  /** The lowest threshold HikariCP keeps, in milliseconds. */
  private static final long LOWEST_THRESHOLD = 2000;

  /** HikariCP's own {@code max-lifetime}, in milliseconds: 30 minutes. */
  private static final long HIKARI_MAX_LIFETIME = 1_800_000;

  /** The key of a named pool's leak alarm threshold, after its HikariCP prefix. */
  private static final String THRESHOLD = "leak-detection-threshold";

  /** The key of a named pool's connection lifetime, after its HikariCP prefix. */
  private static final String LIFETIME = "max-lifetime";

  /**
   * Checks the main pool's threshold, in any of Spring Boot's spellings of the key. A threshold of
   * 0, HikariCP's default, means the alarm is off and is not checked; so is a {@code max-lifetime}
   * of 0, which HikariCP reads as no limit.
   *
   * @param environment the application's environment
   * @throws StratumException {@code SX-DATA-1002} if HikariCP would turn the alarm off
   */
  void checkMainPool(Environment environment) {
    checkPool(
        Binder.get(environment),
        DataConstants.LEAK_DETECTION_THRESHOLD,
        DataConstants.MAX_LIFETIME);
  }

  /**
   * Checks each named pool's threshold in the settings, which include the pools a Java customizer
   * adds. A pool's own settings win over its keys, as when the pool is built.
   *
   * @param environment the application's environment, with the pools' keys
   * @param settings the module's settings
   * @throws StratumException {@code SX-DATA-1002} if HikariCP would turn a pool's alarm off
   */
  void checkNamedPools(Environment environment, DataProperties settings) {
    settings.sources().forEach((name, pool) -> checkNamedPool(environment, name, pool));
  }

  /** Checks one named pool's threshold against its lifetime. */
  private void checkNamedPool(Environment environment, String name, PoolSettings pool) {
    var prefix = DataConstants.SOURCES + "." + name + "." + DataConstants.HIKARI_KEYS + ".";
    var binder = new Binder(sources(environment, prefix, pool.hikari()));
    checkPool(binder, prefix + THRESHOLD, prefix + LIFETIME);
  }

  /** Returns the pool's own settings, under its keys' prefix, ahead of the environment. */
  private Iterable<ConfigurationPropertySource> sources(
      Environment environment, String prefix, Map<String, String> hikari) {
    var own =
        new MapConfigurationPropertySource(
            hikari.entrySet().stream()
                .collect(Collectors.toMap(entry -> prefix + entry.getKey(), Map.Entry::getValue)));
    var keys =
        StreamSupport.stream(ConfigurationPropertySources.get(environment).spliterator(), false);
    return Stream.concat(Stream.of(own), keys).toList();
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
