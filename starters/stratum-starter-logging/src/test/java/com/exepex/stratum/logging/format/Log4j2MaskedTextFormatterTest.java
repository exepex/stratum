package com.exepex.stratum.logging.format;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.logging.testsupport.LoggingTestData;
import com.exepex.stratum.logging.testsupport.MaskedTextFormats;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.ContextDataFactory;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.structured.StructuredLogFormatter;

@DisplayName("Log4j2MaskedTextFormatter writes each Log4j2 event as one masked line of text")
class Log4j2MaskedTextFormatterTest {

  /** The time every test event happened, in milliseconds since the epoch: 2026-11-02T10:15:30Z. */
  private static final long TIME_MILLIS = 1_793_614_530_123L;

  /** The format, created by Spring Boot from its class name. */
  private final StructuredLogFormatter<LogEvent> formatter =
      MaskedTextFormats.createdBySpringBoot(
          LogEvent.class,
          Log4j2MaskedTextFormatter.class.getName(),
          LoggingTestData.environmentWithModuleOn());

  /** Returns an INFO event from the {@code orders} logger on thread {@code main}. */
  private Log4jLogEvent.Builder event(String message, @Nullable Throwable exception) {
    return Log4jLogEvent.newBuilder()
        .setTimeMillis(TIME_MILLIS)
        .setLevel(Level.INFO)
        .setThreadName("main")
        .setLoggerName("orders")
        .setMessage(new SimpleMessage(message))
        .setThrown(exception);
  }

  @Test
  @DisplayName("holds the time, level, thread, logger and message, and ends with a line break")
  void writesLayout() {
    assertThat(formatter.format(event("order placed", null).build()))
        .isEqualTo("2026-11-02T10:15:30.123Z  INFO [main] orders : order placed\n");
  }

  @Test
  @DisplayName("masks a secret in the message and an MDC value by its key, each on one line")
  void masksMessageAndContextData() {
    var contextData = ContextDataFactory.createContextData();
    contextData.putValue("api_key", LoggingTestData.PASSWORD);
    contextData.putValue("note", "first\nsecond");

    assertThat(
            formatter.format(
                event("login password=" + LoggingTestData.PASSWORD, null)
                    .setContextData(contextData)
                    .build()))
        .contains("login password=****", "api_key=****", "note=first\\nsecond")
        .doesNotContain(LoggingTestData.PASSWORD)
        .hasLineCount(1);
  }

  @Test
  @DisplayName("masks the stack trace and indents its following lines")
  void masksStackTrace() {
    var failure = new IllegalStateException("token=" + LoggingTestData.JWT + "\nforged line");

    var line = formatter.format(event("payment failed", failure).build());

    assertThat(line)
        .contains("IllegalStateException: token=****")
        .doesNotContain(LoggingTestData.JWT);
    MaskedTextFormats.assertStackTraceIndented(line);
  }
}
