package com.exepex.stratum.logging.format;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.LoggingEventVO;
import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.testsupport.LoggingTestData;
import com.exepex.stratum.logging.testsupport.MaskedTextFormats;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("LogbackMaskedTextFormatter writes each Logback event as one masked line of text")
class LogbackMaskedTextFormatterTest {

  /** The logger of most test events. */
  private static final String ORDERS = "orders";

  /** The message of the test events with an exception. */
  private static final String PAYMENT_FAILED = "payment failed";

  /** The time every test event happened. */
  private static final Instant TIME = Instant.parse("2026-11-02T10:15:30.123Z");

  /** A message whose second line would look like a log line of its own. */
  private static final String FORGED_LINE =
      "bad input\n2026-11-02T10:00:00Z  INFO [main] audit : admin logged in";

  /** The letter é in ISO-8859-1, where it is one byte. */
  private static final byte E_ACUTE_IN_LATIN_1 = (byte) 0xE9;

  /** An exception message four times the default max-field-length of 16KB. */
  private static final String OVERSIZED_MESSAGE =
      "x".repeat((int) LoggingTestData.DEFAULT_MAX_FIELD_LENGTH.toBytes() * 4);

  /** The longest a line with a cut stack trace may be: the limit plus the line's other parts. */
  private static final long CUT_LINE_MAX_LENGTH =
      LoggingTestData.DEFAULT_MAX_FIELD_LENGTH.toBytes() + 1024;

  /** The environment with the module on, as a service has it. */
  private final MockEnvironment moduleOn = LoggingTestData.environmentWithModuleOn();

  /** The format, created by Spring Boot from its class name. */
  private final StructuredLogFormatter<ILoggingEvent> formatter =
      MaskedTextFormats.createdBySpringBoot(
          ILoggingEvent.class, LogbackMaskedTextFormatter.class.getName(), moduleOn);

  /** Returns an INFO event from the {@code orders} logger on thread {@code main}, with no MDC. */
  private LoggingEvent event(String message, @Nullable Throwable exception) {
    return event(ORDERS, message, exception, Map.of());
  }

  /** Returns an INFO event from the logger, on thread {@code main}. */
  private LoggingEvent event(
      String loggerName, String message, @Nullable Throwable exception, Map<String, String> mdc) {
    return event("main", loggerName, message, exception, mdc);
  }

  /** Returns an INFO event from the logger, on the thread. */
  private LoggingEvent event(
      String threadName,
      String loggerName,
      String message,
      @Nullable Throwable exception,
      Map<String, String> mdc) {
    var event =
        new LoggingEvent(
            null, new LoggerContext().getLogger(loggerName), Level.INFO, message, exception, null);
    event.setInstant(TIME);
    event.setThreadName(threadName);
    event.setMDCPropertyMap(mdc);
    return event;
  }

  @Nested
  @DisplayName("the line")
  class Line {

    @Test
    @DisplayName("holds the time, level, thread, logger and message, and ends with a line break")
    void writesLayout() {
      assertThat(formatter.format(event("order placed", null)))
          .isEqualTo("2026-11-02T10:15:30.123Z  INFO [main] orders : order placed\n");
    }

    @Test
    @DisplayName("ends with each MDC entry, then each key-value pair, as key=value")
    void writesFields() {
      var event = event(ORDERS, "order placed", null, Map.of("traceId", "abc123"));
      event.addKeyValuePair(new KeyValuePair("items", LoggingTestData.ITEM_COUNT));

      assertThat(formatter.format(event)).endsWith(" : order placed traceId=abc123 items=3\n");
    }

    @Test
    @DisplayName("is written in the output's charset")
    void writesInCharset() {
      assertThat(formatter.formatAsBytes(event("café", null), StandardCharsets.ISO_8859_1))
          .contains(E_ACUTE_IN_LATIN_1);
    }
  }

  @Nested
  @DisplayName("masking")
  class Masking {

    @Test
    @DisplayName("masks a secret in the message")
    void masksMessage() {
      assertThat(formatter.format(event("login password=" + LoggingTestData.PASSWORD, null)))
          .contains("login password=****")
          .doesNotContain(LoggingTestData.PASSWORD);
    }

    @Test
    @DisplayName("masks an MDC value and a key-value pair by their keys")
    void masksFieldsByKey() {
      var event = event(ORDERS, "checked", null, Map.of("api_key", LoggingTestData.PASSWORD));
      event.addKeyValuePair(new KeyValuePair("email", LoggingTestData.EMAIL));

      assertThat(formatter.format(event))
          .contains("api_key=****", "email=****.com")
          .doesNotContain(LoggingTestData.PASSWORD, LoggingTestData.EMAIL);
    }

    @Test
    @DisplayName("masks a secret in the thread name and the logger name")
    void masksThreadAndLogger() {
      var event =
          event(
              "worker-" + LoggingTestData.CARD,
              "card-" + LoggingTestData.CARD,
              "checked",
              null,
              Map.of());

      assertThat(formatter.format(event)).doesNotContain(LoggingTestData.CARD);
    }

    @Test
    @DisplayName("masks a secret in an exception's message and in its stack trace")
    void masksStackTrace() {
      var failure = new IllegalStateException("token=" + LoggingTestData.JWT);

      assertThat(formatter.format(event(PAYMENT_FAILED, failure)))
          .contains("java.lang.IllegalStateException: token=****")
          .doesNotContain(LoggingTestData.JWT);
    }

    @Test
    @DisplayName("masks a stack trace read back from another process, which has only its text")
    void masksStackTraceWithoutException() {
      var failure = new IllegalStateException("token=" + LoggingTestData.JWT);
      var readBack = LoggingEventVO.build(event(PAYMENT_FAILED, failure));

      assertThat(formatter.format(readBack))
          .contains("IllegalStateException: token=****")
          .doesNotContain(LoggingTestData.JWT);
    }

    @Test
    @DisplayName("cuts a stack trace longer than max-field-length")
    void cutsLongStackTrace() {
      var failure = new IllegalStateException(OVERSIZED_MESSAGE);

      var line = formatter.format(event(PAYMENT_FAILED, failure));

      assertThat(line).hasSizeLessThan((int) CUT_LINE_MAX_LENGTH).contains("...[truncated]");
    }
  }

  @Nested
  @DisplayName("line breaks")
  class LineBreaks {

    @Test
    @DisplayName("in the message are shown as \\r and \\n, so the event stays on one line")
    void keepsMessageOnOneLine() {
      assertThat(formatter.format(event(FORGED_LINE + "\r", null)))
          .endsWith(" : bad input\\n2026-11-02T10:00:00Z  INFO [main] audit : admin logged in\\r\n")
          .hasLineCount(1);
    }

    @Test
    @DisplayName("in an MDC key or value and in a key-value pair are shown as \\r and \\n")
    void keepsFieldsOnOneLine() {
      var event = event(ORDERS, "checked", null, Map.of("user\nkey", FORGED_LINE));
      event.addKeyValuePair(new KeyValuePair("note", FORGED_LINE));

      assertThat(formatter.format(event)).contains("user\\nkey=bad input\\n2026").hasLineCount(1);
    }

    @Test
    @DisplayName(
        "in an exception's message are indented: only the event's own line starts at the left edge")
    void indentsExceptionMessage() {
      var line = formatter.format(event(PAYMENT_FAILED, new IllegalStateException(FORGED_LINE)));

      assertThat(line.lines().skip(1)).allMatch(this::startsWithWhitespace);
    }

    @Test
    @DisplayName("in a cause's message are indented too")
    void indentsCauseMessage() {
      var failure =
          new IllegalStateException(PAYMENT_FAILED, new IllegalArgumentException(FORGED_LINE));

      var line = formatter.format(event(PAYMENT_FAILED, failure));

      assertThat(line).contains("IllegalArgumentException: bad input");
      assertThat(line.lines().skip(1)).allMatch(this::startsWithWhitespace);
    }

    /** Returns whether a line starts with whitespace. */
    private boolean startsWithWhitespace(String line) {
      return !line.isEmpty() && Character.isWhitespace(line.charAt(0));
    }
  }

  @Test
  @DisplayName("a service's extra key is masked in text too")
  void appliesExtraKeys() {
    moduleOn.setProperty(LoggingConstants.PREFIX + ".redaction.extra-keys[0]", "badge_no");
    var event = event(ORDERS, "badge checked", null, Map.of("badge_no", "B-77"));

    assertThat(
            MaskedTextFormats.createdBySpringBoot(
                    ILoggingEvent.class, LogbackMaskedTextFormatter.class.getName(), moduleOn)
                .format(event))
        .contains("badge_no=****");
  }
}
