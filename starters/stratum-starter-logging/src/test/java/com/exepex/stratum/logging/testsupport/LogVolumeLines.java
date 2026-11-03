package com.exepex.stratum.logging.testsupport;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.exepex.stratum.logging.LoggingProperties.Volume;
import com.exepex.stratum.logging.volume.LogVolumeFilter;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.slf4j.MDC;

/**
 * A Logback context of its own with the volume filter in it, a clock the test sets and the lines
 * written, so that a test sees what the filter keeps without touching the JVM's logging.
 */
public class LogVolumeLines {

  /** The second the clock starts at. */
  public static final long START_SECOND = 100;

  /** Milliseconds in a second. */
  private static final long MILLIS_PER_SECOND = 1000;

  /** The test's own Logback context. */
  private final LoggerContext context = new LoggerContext();

  /** Every line written in the context, with its MDC as it was when written. */
  private final ListAppender<ILoggingEvent> written =
      new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent line) {
          line.prepareForDeferredProcessing();
          super.append(line);
        }
      };

  /** The current time, in milliseconds. */
  private final AtomicLong millis = new AtomicLong(START_SECOND * MILLIS_PER_SECOND);

  /**
   * Creates the context with the filter in it and every level on.
   *
   * @param settings the filter's settings
   */
  public LogVolumeLines(Volume settings) {
    var clock = mock(Clock.class);
    when(clock.millis()).thenAnswer(call -> millis.get());
    // The same MDC as SLF4J's, so that lines carry the fields a test puts.
    context.setMDCAdapter(MDC.getMDCAdapter());
    var filter = new LogVolumeFilter(settings, clock);
    filter.setContext(context);
    filter.start();
    context.addTurboFilter(filter);
    written.setContext(context);
    written.start();
    var root = context.getLogger(Logger.ROOT_LOGGER_NAME);
    root.setLevel(Level.TRACE);
    root.addAppender(written);
  }

  /**
   * Returns a logger of the context.
   *
   * @param name the logger's name
   * @return the logger
   */
  public Logger logger(String name) {
    return context.getLogger(name);
  }

  /**
   * Moves the clock to a second.
   *
   * @param second seconds after the start
   */
  public void atSecond(long second) {
    millis.set((START_SECOND + second) * MILLIS_PER_SECOND);
  }

  /**
   * Writes the same INFO line a number of times.
   *
   * @param name the logger's name
   * @param times how many lines
   */
  public void writeInfo(String name, int times) {
    var logger = logger(name);
    IntStream.range(0, times).forEach(line -> logger.info("line {}", line));
  }

  /**
   * Returns the lines written so far.
   *
   * @return the lines, in order
   */
  public List<ILoggingEvent> lines() {
    return List.copyOf(written.list);
  }

  /**
   * Returns how many lines of a level were written.
   *
   * @param level the level
   * @return the count
   */
  public long count(Level level) {
    return written.list.stream().filter(line -> level.equals(line.getLevel())).count();
  }
}
