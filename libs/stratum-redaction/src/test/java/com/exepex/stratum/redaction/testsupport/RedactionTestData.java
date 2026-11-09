package com.exepex.stratum.redaction.testsupport;

import com.exepex.stratum.redaction.ContentMaskingRules;
import com.exepex.stratum.redaction.SensitiveFieldNames;
import java.util.List;
import java.util.function.UnaryOperator;
import lombok.experimental.UtilityClass;

/**
 * Fake sensitive values and the built-in rules, shared by the redaction tests. Every value is made
 * up, such as the well-known test card number {@code 4111 1111 1111 1111}.
 */
@UtilityClass
public class RedactionTestData {

  /** A made-up password. */
  public static final String PASSWORD = "hunter2-fake";

  /** A test card number that passes the Luhn check. */
  public static final String CARD = "4111111111111111";

  /** A made-up email address. */
  public static final String EMAIL = "jane.doe@example.com";

  /** A made-up international phone number. */
  public static final String PHONE = "+44 20 7946 0958";

  /** A valid example IBAN. */
  public static final String IBAN = "GB82WEST12345698765432";

  /** The built-in names, with no names of the service's own. */
  public static final SensitiveFieldNames BUILT_IN_NAMES = new SensitiveFieldNames(List.of());

  /** The built-in rules that mask text by what it looks like. */
  public static final UnaryOperator<String> BUILT_IN_RULES =
      ContentMaskingRules.builtInRules(BUILT_IN_NAMES);

  /**
   * Masks text that has no name, as in a log message, with the built-in rules.
   *
   * @param text the text to mask
   * @return the masked text
   */
  public static String maskUnnamedText(String text) {
    return BUILT_IN_RULES.apply(text);
  }

  /**
   * Masks a value by its name with the built-in names, or else by what it looks like.
   *
   * @param name the value's name
   * @param value the value
   * @return the masked value
   */
  public static String maskByName(String name, String value) {
    return maskByName(BUILT_IN_NAMES, name, value);
  }

  /**
   * Masks a value by its name with the given names, or else by what it looks like.
   *
   * @param fieldNames the names whose values are masked
   * @param name the value's name
   * @param value the value
   * @return the masked value
   */
  public static String maskByName(SensitiveFieldNames fieldNames, String name, String value) {
    return fieldNames
        .mask(name, () -> value)
        .orElseGet(() -> ContentMaskingRules.builtInRules(fieldNames).apply(value));
  }
}
