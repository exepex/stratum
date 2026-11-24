package com.exepex.stratum.test;

import com.exepex.stratum.core.api.StratumInternal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import lombok.RequiredArgsConstructor;
import lombok.experimental.UtilityClass;
import lombok.extern.java.Log;

/**
 * Captures each wrong use of OpenTelemetry's API that OpenTelemetry reports while a test's work
 * runs, such as a null baggage value. OpenTelemetry warns only once per JVM but reports every case
 * at its finest level, which the capture turns on, so a test sees each case whatever ran before it.
 */
@StratumInternal
@UtilityClass
// The JDK keeps a logger's level only while the logger is referenced, so this class holds it.
@Log(topic = OpenTelemetryUsageIssues.USAGE_LOGGER_NAME)
public class OpenTelemetryUsageIssues {

  /** The name of the JDK logger that OpenTelemetry reports a wrong use of its API to. */
  static final String USAGE_LOGGER_NAME = "io.opentelemetry.usage";

  /**
   * Runs the work and returns each case OpenTelemetry reported meanwhile, from any thread.
   *
   * @param work the work, such as a request to the service
   * @return each case's message, such as {@code BaggageBuilder.put(): value is null}, in order
   * @throws Exception what the work throws, unchanged
   */
  public List<String> during(Callable<?> work) throws Exception {
    var issues = new CopyOnWriteArrayList<String>();
    var handler = new IssueHandler(issues);
    var levelBefore = log.getLevel();
    log.setLevel(Level.FINEST);
    log.addHandler(handler);
    try {
      work.call();
    } finally {
      log.removeHandler(handler);
      log.setLevel(levelBefore);
    }
    return List.copyOf(issues);
  }

  /** Keeps the message of each record it is given. */
  @RequiredArgsConstructor
  private static final class IssueHandler extends Handler {

    /** The messages kept, in a list that is safe to add to from any thread. */
    private final List<String> issues;

    @Override
    public void publish(LogRecord issue) {
      issues.add(issue.getMessage());
    }

    @Override
    public void flush() {
      // Nothing is buffered.
    }

    @Override
    public void close() {
      // Nothing is held open.
    }
  }
}
