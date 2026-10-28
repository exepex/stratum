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

  /** A module name given to {@code @ConditionalOnStratumModule} is not kebab-case. */
  MODULE_NAME_NOT_KEBAB_CASE(
      "SX-CORE-1001",
      Severity.ERROR,
      "@ConditionalOnStratumModule needs a lower-case, kebab-case module name"
          + " such as \"http-client\", but got \"{}\"."),

  /** A settings customizer returned {@code null}. */
  CUSTOMIZER_RETURNED_NULL(
      "SX-CORE-1002",
      Severity.ERROR,
      "{} returned null. Return the settings it received to leave them unchanged."),

  /** A settings customizer returned settings of another type. */
  CUSTOMIZER_FOR_OTHER_SETTINGS(
      "SX-CORE-1003",
      Severity.ERROR,
      "{} returned settings of another type: it is a customizer for other settings, or it was"
          + " registered without its type. To fix it, declare it as"
          + " StratumPropertiesCustomizer<{}>, for example as the return type of a @Bean method."),

  /** A settings customizer failed with a {@code ClassCastException}. */
  CUSTOMIZER_CAST_FAILED(
      "SX-CORE-1004",
      Severity.ERROR,
      "{} failed with a ClassCastException. If it is a customizer for other settings, or was"
          + " registered without its type, declare it as StratumPropertiesCustomizer<{}>, for"
          + " example as the return type of a @Bean method. Otherwise the cast that failed is in"
          + " the customizer's own code; see the cause."),

  /** A module's settings break their constraints. */
  INVALID_SETTINGS("SX-CORE-1005", Severity.ERROR, "Invalid {}: {}");

  /** The code, such as {@code SX-CORE-1001}. */
  private final String code;

  /** Whether the operation failed or only needs attention. */
  private final Severity severity;

  /** What went wrong and how to fix it, with placeholders for the details. */
  private final String description;
}
