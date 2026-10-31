package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

@DisplayName("LogLineCapture keeps one logger's lines with the context they were written in")
class LogLineCaptureTest {

  /** The logger the test writes to. */
  private static final String LOGGER = "com.exepex.stratum.test.orders";

  /** The capture under test. */
  private final LogLineCapture capture = new LogLineCapture(LOGGER);

  @Test
  @DisplayName("keeps the line's fields and logging context after the context is cleared")
  void keepsFieldsAndContext() {
    capture.start();
    MDC.put("orderId", "42");
    LoggerFactory.getLogger(LOGGER)
        .atInfo()
        .setMessage("placed")
        .addKeyValue("items", "three")
        .log();
    MDC.clear();
    capture.stop();

    var line = capture.onlyLine();
    assertThat(line.getMDCPropertyMap()).containsEntry("orderId", "42");
    assertThat(capture.fieldsOf(line)).isEqualTo(Map.of("items", "three"));
  }

  @Test
  @DisplayName("keeps no line written after it stopped")
  void stopsCapturing() {
    capture.start();
    capture.stop();
    LoggerFactory.getLogger(LOGGER).info("placed");

    assertThat(capture.lines()).isEmpty();
  }
}
