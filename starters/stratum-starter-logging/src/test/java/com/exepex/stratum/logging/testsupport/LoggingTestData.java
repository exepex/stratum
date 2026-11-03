package com.exepex.stratum.logging.testsupport;

import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.LoggingProperties;
import com.exepex.stratum.logging.LoggingProperties.LogFormat;
import com.exepex.stratum.logging.redaction.Redactor;
import com.exepex.stratum.logging.startup.LoggingEnvironmentPostProcessor;
import java.util.List;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.json.JsonWriter;
import org.springframework.mock.env.MockEnvironment;
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

  /** The test card {@link #CARD} written in groups, as a person types it. */
  public static final String CARD_IN_GROUPS = "4111 1111 1111 1111";

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

  /** The default {@code max-elements}. */
  public static final int DEFAULT_MAX_ELEMENTS = 10;

  /** A made-up JSON Web Token. */
  public static final String JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmYWtlIn0.c2lnbmF0dXJlLWZha2U";

  /** Masks with the default settings; the redaction tests share it. */
  public static final Redactor DEFAULT_REDACTOR = Redactor.from(defaults().build());

  /**
   * Returns a builder for the module's settings, with every default.
   *
   * @return ECS, a 16KB limit, 10 elements and no extra rules
   */
  public static LoggingProperties.LoggingPropertiesBuilder defaults() {
    return LoggingProperties.builder()
        .format(LogFormat.ECS)
        .maxFieldLength(DEFAULT_MAX_FIELD_LENGTH)
        .maxElements(DEFAULT_MAX_ELEMENTS)
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
   * @return the settings, ready to change further and build
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
   * Returns an environment with a service name, which the module needs to start.
   *
   * @return the environment, which a test can add properties to
   */
  public static MockEnvironment environmentWithServiceName() {
    return new MockEnvironment().withProperty(LoggingConstants.APPLICATION_NAME, "orders");
  }

  /**
   * Runs the logging module's startup post-processor on the environment, as Spring Boot does at
   * startup.
   *
   * @param environment the environment to set up
   * @return the same environment, with the module's defaults added
   */
  public static MockEnvironment runLoggingPostProcessor(MockEnvironment environment) {
    return runLoggingPostProcessor(environment, new SpringApplication());
  }

  /**
   * Runs the logging module's startup post-processor on the environment for the application.
   *
   * @param environment the environment to set up
   * @param application the application being started, such as one without Logback
   * @return the same environment, with the module's defaults added
   */
  public static MockEnvironment runLoggingPostProcessor(
      MockEnvironment environment, SpringApplication application) {
    new LoggingEnvironmentPostProcessor().postProcessEnvironment(environment, application);
    return environment;
  }

  /**
   * Masks text that has no field name, as in a log message, with the default settings.
   *
   * @param text the text to mask
   * @return the masked text
   */
  public static String redactUnnamedText(String text) {
    return (String) DEFAULT_REDACTOR.redact(null, text);
  }

  /**
   * Masks a value as a JSON log line field, with the default settings, and returns the JSON that
   * Spring Boot writes for it, such as {@code 42} for a number or {@code "****"} for masked text.
   *
   * @param fieldName the value's field name
   * @param value the value to mask
   * @return the JSON written for the value
   */
  public static String redactedAsJson(String fieldName, @Nullable Object value) {
    return JsonWriter.standard().write(DEFAULT_REDACTOR.redact(fieldName, value)).toJsonString();
  }

  /**
   * Returns an environment with the module switched on and the console's JSON format set, as Spring
   * Boot's {@code logging.structured.format.console}.
   *
   * @param format Spring Boot's name for the format, such as {@code ecs}
   * @return the environment, which a test can add properties to
   */
  public static MockEnvironment environmentWithConsoleFormat(String format) {
    return new MockEnvironment().withProperty(LoggingConstants.CONSOLE_FORMAT, format);
  }
}
