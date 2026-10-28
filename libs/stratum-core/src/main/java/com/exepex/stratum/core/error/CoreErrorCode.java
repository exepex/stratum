package com.exepex.stratum.core.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The errors and warnings of {@code stratum-core}, each listed with its fix in {@code
 * docs/guides/error-codes.md}. Every error here stops the application at startup.
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
  INVALID_SETTINGS("SX-CORE-1003", Severity.ERROR, "Invalid settings");

  /** The code, such as {@code SX-CORE-1001}. */
  private final String code;

  /** Whether the operation failed or only needs attention. */
  private final Severity severity;

  /** What went wrong, in fixed text. */
  private final String description;
}
