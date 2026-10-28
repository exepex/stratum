package com.exepex.stratum.core.properties;

/**
 * Changes a Stratum module's settings in Java code. Declare one as a bean when a service prefers
 * code to {@code application.properties} or {@code application.yml}, or when its configuration
 * files would grow too large. The settings it returns win over the same settings in those files.
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
