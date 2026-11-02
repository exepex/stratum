package com.exepex.stratum.logging.testsupport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.exepex.stratum.logging.redaction.RedactingJsonMembersCustomizer;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import lombok.experimental.UtilityClass;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.xml.XmlConfiguration;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.json.JsonWriter.Members;
import org.springframework.boot.logging.log4j2.Log4J2LoggingSystem;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.Conventions;
import org.springframework.core.env.Environment;

/**
 * Writes JSON log lines as Spring Boot writes them, masked by Stratum: one field through the
 * redaction customizer, or a whole line in one of Spring Boot's formats through its own Logback
 * encoder or Log4j2 layout, with every customizer listed in {@code META-INF/spring.factories}.
 */
@UtilityClass
public class SpringBootJsonLines {

  /** Where Spring Boot keeps the environment in a Log4j2 logger context. */
  private static final String LOG4J2_ENVIRONMENT_KEY =
      Conventions.getQualifiedAttributeName(Log4J2LoggingSystem.class, "environment");

  /** A Log4j2 configuration with one appender that writes the format named by {@code %s}. */
  private static final String LOG4J2_CONFIGURATION =
      """
      <Configuration>
        <Appenders><Console name="out"><StructuredLogLayout format="%s"/></Console></Appenders>
        <Loggers><Root level="info"><AppenderRef ref="out"/></Root></Loggers>
      </Configuration>
      """;

  /**
   * Returns a JSON object with one field, masked as Stratum masks every field of a log line, with
   * the module switched on and the environment's other settings.
   *
   * @param environment the application's environment
   * @param fieldName the field's name
   * @param value the field's value
   * @return the JSON object
   */
  public static String redactedLine(
      Environment environment, String fieldName, @Nullable Object value) {
    var customizer = new RedactingJsonMembersCustomizer(environment);
    return JsonWriter.<Object>of(members -> addField(members, fieldName, value, customizer))
        .writeToString(fieldName);
  }

  /** Adds the field, then lets the customizer mask it. */
  private static void addField(
      Members<Object> members,
      String fieldName,
      @Nullable Object value,
      RedactingJsonMembersCustomizer customizer) {
    members.add(fieldName, value);
    customizer.customize(members);
  }

  /**
   * Returns an INFO line with the message, written by Logback's structured encoder.
   *
   * @param format Spring Boot's name for the format, such as {@code gelf}
   * @param environment the application's environment
   * @param message the message
   * @return the line
   */
  public static String logbackLine(String format, Environment environment, String message) {
    var context = new LoggerContext();
    context.putObject(Environment.class.getName(), environment);
    var encoder = new StructuredLogEncoder();
    encoder.setContext(context);
    encoder.setFormat(format);
    encoder.start();
    var event =
        new LoggingEvent(null, context.getLogger("orders"), Level.INFO, message, null, null);
    return new String(encoder.encode(event), StandardCharsets.UTF_8);
  }

  /**
   * Returns an INFO line with the message, written by Log4j2's structured layout.
   *
   * @param format Spring Boot's name for the format, such as {@code gelf}
   * @param environment the application's environment
   * @param message the message
   * @return the line
   * @throws java.io.IOException never, as the configuration is read from memory
   */
  public static String log4j2Line(String format, Environment environment, String message)
      throws java.io.IOException {
    var context = new org.apache.logging.log4j.core.LoggerContext("orders");
    context.putObject(LOG4J2_ENVIRONMENT_KEY, environment);
    var xml = LOG4J2_CONFIGURATION.formatted(format).getBytes(StandardCharsets.UTF_8);
    var configuration =
        new XmlConfiguration(context, new ConfigurationSource(new ByteArrayInputStream(xml)));
    context.start(configuration);
    try {
      var event =
          Log4jLogEvent.newBuilder()
              .setLoggerName("orders")
              .setLevel(org.apache.logging.log4j.Level.INFO)
              .setMessage(new SimpleMessage(message))
              .build();
      return configuration.getAppender("out").getLayout().toSerializable(event).toString();
    } finally {
      context.stop();
    }
  }
}
