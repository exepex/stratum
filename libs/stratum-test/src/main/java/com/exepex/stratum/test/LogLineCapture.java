package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;

/**
 * Captures the lines one logger writes, so a test can check a line's message, fields and logging
 * context. A test calls {@link #start} before the code under test runs and {@link #stop} after.
 */
public class LogLineCapture {

  /** The logger whose lines are captured. */
  private final Logger logger;

  /**
   * Keeps each line with a copy of the logging context it was written in, because the code that
   * wrote it may clear the context straight after.
   */
  private final ListAppender<ILoggingEvent> appender =
      new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent line) {
          line.prepareForDeferredProcessing();
          super.append(line);
        }
      };

  /**
   * Creates a capture of one logger's lines.
   *
   * @param loggerName the name of the logger
   */
  public LogLineCapture(String loggerName) {
    logger = (Logger) LoggerFactory.getLogger(loggerName);
  }

  /** Starts capturing the logger's lines. */
  public void start() {
    appender.start();
    logger.addAppender(appender);
  }

  /** Stops capturing the logger's lines. */
  public void stop() {
    logger.detachAppender(appender);
    appender.stop();
  }

  /**
   * Returns the lines captured so far.
   *
   * @return the lines, in the order written
   */
  public List<ILoggingEvent> lines() {
    return List.copyOf(appender.list);
  }

  /**
   * Waits until a line is written, as by a server thread, then checks that it is the only one.
   *
   * @return the only line
   */
  public ILoggingEvent onlyLine() {
    await().untilAsserted(() -> assertThat(appender.list).isNotEmpty());
    assertThat(appender.list).hasSize(1);
    return appender.list.getFirst();
  }

  /**
   * Returns the key-value pairs a line was written with.
   *
   * @param line the line
   * @return each key with its value
   */
  public Map<String, Object> fieldsOf(ILoggingEvent line) {
    return line.getKeyValuePairs().stream()
        .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
  }
}
