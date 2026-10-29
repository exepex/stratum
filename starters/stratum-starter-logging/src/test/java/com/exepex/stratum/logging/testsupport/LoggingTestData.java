package com.exepex.stratum.logging.testsupport;

import com.exepex.stratum.logging.LoggingProperties;
import com.exepex.stratum.logging.LoggingProperties.LogFormat;
import java.util.List;
import lombok.experimental.UtilityClass;
import org.springframework.util.unit.DataSize;

/**
 * Settings and fake sensitive values shared by the logging tests. Every value is made up, such as
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

  /** The default {@code max-field-length}. */
  public static final DataSize DEFAULT_MAX_FIELD_LENGTH = DataSize.ofKilobytes(16);

  /** A made-up JSON Web Token. */
  public static final String JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmYWtlIn0.c2lnbmF0dXJlLWZha2U";

  /**
   * Returns the module's settings with every default.
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
   * @return the settings
   */
  public static LoggingProperties withRedaction(
      List<String> extraKeys, List<String> extraPatterns) {
    return defaults()
        .redaction(
            LoggingProperties.Redaction.builder()
                .extraKeys(extraKeys)
                .extraPatterns(extraPatterns)
                .build())
        .build();
  }
}
