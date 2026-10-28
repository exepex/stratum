package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("LoggingContextIds puts sample IDs where tracing and Stratum put the real ones")
class LoggingContextIdsTest {

  /** Leaves the logging context empty for the next test. */
  @AfterEach
  void clearLoggingContext() {
    MDC.clear();
  }

  @Test
  @DisplayName("the trace, span and correlation IDs are in the logging context under their keys")
  void putsEachIdUnderItsKey() {
    LoggingContextIds.putInLoggingContext();

    assertThat(MDC.getCopyOfContextMap())
        .containsEntry("traceId", LoggingContextIds.TRACE_ID)
        .containsEntry("spanId", LoggingContextIds.SPAN_ID)
        .containsEntry("correlationId", LoggingContextIds.CORRELATION_ID);
  }

  @Test
  @DisplayName("an ID already in the logging context is replaced, and other entries are kept")
  void replacesIdsAndKeepsOtherEntries() {
    MDC.put(LoggingContextIds.CORRELATION_ID_KEY, "earlier-id");
    MDC.put("tenant", "north");

    LoggingContextIds.putInLoggingContext();

    assertThat(MDC.getCopyOfContextMap())
        .containsEntry(LoggingContextIds.CORRELATION_ID_KEY, LoggingContextIds.CORRELATION_ID)
        .containsEntry("tenant", "north")
        .containsOnlyKeys(
            LoggingContextIds.TRACE_ID_KEY,
            LoggingContextIds.SPAN_ID_KEY,
            LoggingContextIds.CORRELATION_ID_KEY,
            "tenant");
  }
}
