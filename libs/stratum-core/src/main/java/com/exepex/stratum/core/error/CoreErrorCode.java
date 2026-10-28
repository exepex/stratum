package com.exepex.stratum.core.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The errors of {@code stratum-core}. Each one stops the application at startup and is listed, with
 * its fix, in {@code docs/guides/error-codes.md}.
 */
@Getter
@RequiredArgsConstructor
public enum CoreErrorCode implements ErrorCode {

  /** A module name given to {@code @ConditionalOnStratumModule} is not kebab-case. */
  MODULE_NAME_NOT_KEBAB_CASE(
      "SX-CORE-1001",
      "@ConditionalOnStratumModule needs a lower-case, kebab-case module name"
          + " such as \"http-client\", but got \"{}\"."),

  /** A settings customizer returned {@code null}. */
  CUSTOMIZER_RETURNED_NULL(
      "SX-CORE-1002", "{} returned null. Return the settings it received to leave them unchanged."),

  /**
   * A settings customizer returned settings of another type. The arguments are the customizer, the
   * settings type, and the settings type again for the fix.
   */
  CUSTOMIZER_FOR_OTHER_SETTINGS(
      "SX-CORE-1003",
      "{} cannot change {}: it is a customizer for other settings, or it was registered"
          + " without its type. To fix it, "
          // Qualified, because a simple name cannot refer to a constant declared further down.
          + CoreErrorCode.DECLARE_WITH_TYPE),

  /**
   * A settings customizer failed with a {@code ClassCastException}. The arguments are the
   * customizer, the settings type, and the settings type again for the fix.
   */
  CUSTOMIZER_CAST_FAILED(
      "SX-CORE-1004",
      "{} failed with a ClassCastException while changing {}. If it is a customizer for other"
          + " settings, or was registered without its type, "
          + CoreErrorCode.DECLARE_WITH_TYPE
          + " Otherwise the cast that failed is in the customizer's own code; see the cause."),

  /** A module's settings break their constraints. */
  INVALID_SETTINGS("SX-CORE-1005", "Invalid {}: {}");

  /** How to declare a customizer so that Spring gives it to the right module. */
  private static final String DECLARE_WITH_TYPE =
      "declare it as StratumPropertiesCustomizer<{}>, for example as the return type of a @Bean"
          + " method.";

  /** The code, such as {@code SX-CORE-1001}. */
  private final String code;

  /** What went wrong and how to fix it, with placeholders for the details. */
  private final String description;
}
