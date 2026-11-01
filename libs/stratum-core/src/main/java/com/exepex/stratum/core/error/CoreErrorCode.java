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
          + " http-client"),

  /**
   * A {@code StratumPropertiesCustomizer} returned {@code null} or settings of another type, or
   * failed with a {@code ClassCastException}. The detail says which, and names the customizer.
   */
  CUSTOMIZER_BROKEN("SX-CORE-1002", Severity.ERROR, "A StratumPropertiesCustomizer is broken"),

  /** A module's settings break their rules; the detail names each property key and rule. */
  INVALID_SETTINGS("SX-CORE-1003", Severity.ERROR, "Invalid settings"),

  /**
   * A module's customizer is declared as a bean, but modules read customizers only from {@code
   * META-INF/spring.factories}. The detail names the beans.
   */
  CUSTOMIZER_DECLARED_AS_BEAN(
      "SX-CORE-1004",
      Severity.ERROR,
      "A StratumPropertiesCustomizer is declared as a bean, which Stratum ignores; list its class"
          + " in META-INF/spring.factories under the module's customizer interface instead"),

  /**
   * Warning: the running Spring Boot version is outside the line this Stratum release supports. The
   * detail names the version found and the supported line.
   */
  SPRING_BOOT_VERSION_UNSUPPORTED(
      "SX-CORE-1005",
      Severity.WARNING,
      "Spring Boot's version is outside the line this Stratum release supports, so the service may"
          + " fail in ways that are hard to trace; set the Spring Boot version in the parent POM,"
          + " such as your company parent, to the supported line");

  /** The code, such as {@code SX-CORE-1001}. */
  private final String code;

  /** Whether the operation failed or only needs attention. */
  private final Severity severity;

  /** What went wrong, in fixed text. */
  private final String description;
}
