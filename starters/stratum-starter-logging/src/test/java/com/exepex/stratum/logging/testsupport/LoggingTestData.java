package com.exepex.stratum.logging.testsupport;

import com.exepex.stratum.logging.LoggingProperties;
import com.exepex.stratum.logging.LoggingProperties.LogFormat;
import com.exepex.stratum.logging.redaction.Redactor;
import java.util.List;
import lombok.experimental.UtilityClass;
import org.springframework.util.unit.DataSize;

/**
 * Properties and fake sensitive values shared by the logging tests. Every value is made up, such as
 * the well-known test card number {@code 4111 1111 1111 1111}.
 */
@UtilityClass
public class LoggingTestData {

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

  /** A made-up US social security number. */
  public static final String SSN = "123-45-6789";

  /** A made-up numeric PIN. */
  public static final int PIN = 1234;

  /** An ordinary order number, which is never masked. */
  public static final long ORDER_ID = 42L;

  /** An ordinary count of items, which is never masked. */
  public static final int ITEM_COUNT = 3;

  /** The default {@code max-field-length}. */
  public static final DataSize DEFAULT_MAX_FIELD_LENGTH = DataSize.ofKilobytes(16);

  /** Masks with the default properties. */
  private static final Redactor DEFAULT_REDACTOR = Redactor.from(defaults().build());

  /** A made-up JSON Web Token. */
  public static final String JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmYWtlIn0.c2lnbmF0dXJlLWZha2U";

  /**
   * Returns the module's properties with every default.
   *
   * @return ECS, a 16KB limit and no extra rules
   */
  public static LoggingProperties.LoggingPropertiesBuilder defaults() {
    return LoggingProperties.builder()
        .format(LogFormat.ECS)
        .maxFieldLength(DEFAULT_MAX_FIELD_LENGTH)
        .redaction(
            LoggingProperties.Redaction.builder()
                .extraKeys(List.of())
                .extraPatterns(List.of())
                .build());
  }

  /**
   * Returns the defaults with the service's own redaction rules.
   *
   * @param extraKeys the extra names to mask
   * @param extraPatterns the extra patterns to mask
   * @return the properties, ready to change further and build
   */
  public static LoggingProperties.LoggingPropertiesBuilder withRedaction(
      List<String> extraKeys, List<String> extraPatterns) {
    return defaults()
        .redaction(
            LoggingProperties.Redaction.builder()
                .extraKeys(extraKeys)
                .extraPatterns(extraPatterns)
                .build());
  }

  /**
   * Masks text that has no field name, as in a log message, with the default properties.
   *
   * @param text the text to mask
   * @return the masked text
   */
  public static String redactUnnamedText(String text) {
    return (String) DEFAULT_REDACTOR.redact(null, text);
  }
}
