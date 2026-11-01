package com.exepex.stratum.core.properties;

/**
 * Changes a Stratum module's settings in Java code, for a service that prefers code to {@code
 * application.properties} or {@code application.yml}, or whose files would grow too large. The
 * settings it returns win over the same settings in those files.
 *
 * <p>Implement the module's customizer interface, such as {@code LoggingPropertiesCustomizer}, and
 * list the class in {@code META-INF/spring.factories} under that interface. Do not declare it as a
 * bean: that stops startup with {@code SX-CORE-1004}. Customizers are read before any bean exists,
 * because modules such as logging need their settings from the first log line, and every module
 * follows the same rule.
 *
 * <p>The customizer receives the settings already bound from properties, with Stratum's defaults
 * for anything left unset, and returns the settings the module should use. It changes only what it
 * needs and copies the rest. When a service declares several customizers for the same settings,
 * they run in {@link org.springframework.core.annotation.Order @Order} order, each receiving the
 * result of the one before.
 *
 * @param <P> the module's settings type, such as the record bound to {@code stratum.<module>.*}
 */
@FunctionalInterface
public interface StratumPropertiesCustomizer<P> {

  /**
   * Returns the settings the module should use.
   *
   * @param properties the settings bound from properties, or returned by the previous customizer
   * @return the settings to use; never {@code null}, so return {@code properties} to keep them
   */
  P customize(P properties);
}
