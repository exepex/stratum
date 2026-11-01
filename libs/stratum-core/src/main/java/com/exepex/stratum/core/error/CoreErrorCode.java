package com.exepex.stratum.core.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The codes of {@code stratum-core}, each listed with its fix in {@code
 * docs/guides/error-codes.md}. Every error here stops the application at startup; the warning only
 * logs.
 */
@Getter
@RequiredArgsConstructor
public enum CoreErrorCode implements ErrorCode {

  /**
   * A module name given to {@code @ConditionalOnStratumModule} is not one kebab-case word. Only a
   * starter's author can cause it, never a service's configuration.
   */
  MODULE_NAME_NOT_KEBAB_CASE(
      "SX-CORE-1001",
      Severity.ERROR,
      "@ConditionalOnStratumModule needs one lower-case, kebab-case module name, such as"
          + " http-client",
      "Rename the module in the annotation. This is a fault in a starter, not in your service's"
          + " configuration."),

  /**
   * A {@code StratumPropertiesCustomizer} returned {@code null} or settings of another type, or
   * failed with a {@code ClassCastException}. The detail says which, and names the customizer.
   */
  CUSTOMIZER_BROKEN(
      "SX-CORE-1002",
      Severity.ERROR,
      "A StratumPropertiesCustomizer is broken",
      "Return the settings it received to keep them unchanged, and implement the module's"
          + " customizer interface, such as LoggingPropertiesCustomizer. If the cast that failed is"
          + " in the customizer's own code, the cause shows where."),

  /** A module's settings break their rules; the detail names each property key and rule. */
  INVALID_SETTINGS(
      "SX-CORE-1003", Severity.ERROR, "Invalid settings", "Correct the named properties."),

  /**
   * A module's customizer is declared as a bean, but modules read customizers only from {@code
   * META-INF/spring.factories}. The detail names the beans.
   */
  CUSTOMIZER_DECLARED_AS_BEAN(
      "SX-CORE-1004",
      Severity.ERROR,
      "A StratumPropertiesCustomizer is declared as a bean, which Stratum ignores; list its class"
          + " in META-INF/spring.factories under the module's customizer interface instead",
      "Implement the module's customizer interface, such as LoggingPropertiesCustomizer, in a"
          + " class with a public no-argument constructor, list it in META-INF/spring.factories and"
          + " remove the bean."),

  /**
   * Warning: the running Spring Boot version is outside the line this Stratum release supports. The
   * detail names the version found and the supported line.
   */
  SPRING_BOOT_VERSION_UNSUPPORTED(
      "SX-CORE-1005",
      Severity.WARNING,
      "Spring Boot's version is outside the line this Stratum release supports, so the service may"
          + " fail in ways that are hard to trace; set the Spring Boot version in the parent POM,"
          + " such as your company parent, to the supported line",
      ""),

  /**
   * A module is on, but its switch was not {@code true} yet when the modules' startup steps ran, as
   * with a switch set only in a {@code @PropertySource} file, so the module would run half on. The
   * detail names the switch and the property source its value comes from.
   */
  MODULE_SWITCHED_ON_TOO_LATE(
      "SX-CORE-1006",
      Severity.ERROR,
      "A module is switched on where Spring Boot reads it too late for the module's startup steps,"
          + " so it would run half on",
      "Set the switch in application.properties or application.yml, an environment variable or the"
          + " command line. A @PropertySource file loads too late for it."),

  /**
   * A module is off, but its switch was {@code true} when the modules' startup steps ran, so its
   * startup defaults would apply without its beans, and it would run half off. The detail names the
   * switch and the property source that turns it off.
   */
  MODULE_SWITCHED_OFF_TOO_LATE(
      "SX-CORE-1007",
      Severity.ERROR,
      "A module is switched off where Spring Boot reads it too late for the module's startup steps,"
          + " so it would run half off",
      "Set the switch in one place that Spring Boot reads early: application.properties or"
          + " application.yml, an environment variable or the command line. A source added after"
          + " the configuration files is read too late for it.");

  /** The code, such as {@code SX-CORE-1001}. */
  private final String code;

  /** Whether the operation failed or only needs attention. */
  private final Severity severity;

  /** What went wrong, in fixed text. */
  private final String description;

  /** How to fix it, as the Fix column of the error codes page starts; empty for none. */
  private final String fix;
}
