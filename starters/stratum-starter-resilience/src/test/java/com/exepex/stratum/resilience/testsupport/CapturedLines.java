package com.exepex.stratum.resilience.testsupport;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.exepex.stratum.test.LogLineCapture;
import com.exepex.stratum.web.ErrorResponseFactory;
import java.util.List;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Captures one logger's lines during each test, as a JUnit extension a test class registers with
 * {@code @RegisterExtension}, so no test class repeats the start and stop of the capture.
 */
public class CapturedLines implements BeforeEachCallback, AfterEachCallback {

  /** The capture of the logger's lines. */
  private final LogLineCapture capture;

  /**
   * Makes the capture of one logger's lines.
   *
   * @param loggerName the name of the logger, such as a package for all its classes' loggers
   */
  public CapturedLines(String loggerName) {
    capture = new LogLineCapture(loggerName);
  }

  /**
   * Returns the capture of the error response factory's lines, which a refusal must not write while
   * its code has a status.
   *
   * @return the capture
   */
  public static CapturedLines ofErrorResponseFactory() {
    return new CapturedLines(ErrorResponseFactory.class.getName());
  }

  /**
   * Starts capturing before the test.
   *
   * @param context the test's context
   */
  @Override
  public void beforeEach(ExtensionContext context) {
    capture.start();
  }

  /**
   * Stops capturing after the test.
   *
   * @param context the test's context
   */
  @Override
  public void afterEach(ExtensionContext context) {
    capture.stop();
  }

  /**
   * Returns the lines captured during the test so far.
   *
   * @return the lines, in the order written
   */
  public List<ILoggingEvent> lines() {
    return capture.lines();
  }

  /**
   * Waits until a line is written, then checks that it is the only one.
   *
   * @return the only line
   */
  public ILoggingEvent onlyLine() {
    return capture.onlyLine();
  }
}
