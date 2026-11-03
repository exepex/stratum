package com.exepex.stratum.logging.volume;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.logging.LoggingContextConstants;
import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.LoggingProperties.Volume;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.slf4j.Marker;
import org.slf4j.event.LoggingEvent;

/**
 * Drops a log line before Logback builds it when its logger has written its limit of lines at that
 * level in the current second, or when sampling leaves out a DEBUG or INFO line; once per interval
 * it writes one line that counts the dropped ones. It returns only {@code NEUTRAL} or {@code DENY},
 * so Logback's own level check always applies.
 */
@StratumInternal
public final class LogVolumeFilter extends TurboFilter {

  /** The summary's message. */
  static final String SUMMARY = "Dropped log lines over the rate limit";

  /** The sampling rate that keeps every line. */
  private static final int KEEP_ALL = 1;

  /** Milliseconds in a second, the length of one counting window. */
  private static final long MILLIS_PER_SECOND = 1000;

  /** The name of the logger the summary is written to. */
  private static final String SUMMARY_LOGGER = LogVolumeFilter.class.getName();

  /** The sampling rates and how often the summary is written. */
  private final Volume settings;

  /** Tells the current second. */
  private final Clock clock;

  /** The count of each logger and level. */
  private final LogVolumeCounters counters;

  /**
   * Draws which untraced lines sampling keeps. One instance, created at startup, shared by every
   * thread; it is used only when sampling is on and a line has no trace ID.
   */
  private final SecureRandom untracedDraw = new SecureRandom();

  /**
   * The second at or after which the next summary is due; 0 until the first line. One summary per
   * interval of at least a second stays within any WARN limit, so its own logger needs no
   * exemption.
   */
  private final AtomicLong nextSummary = new AtomicLong();

  /**
   * Creates the filter.
   *
   * @param settings the limits, sampling rates, never-limited loggers and summary interval
   * @param clock tells the current second
   */
  public LogVolumeFilter(Volume settings, Clock clock) {
    this.settings = settings;
    this.clock = clock;
    counters = new LogVolumeCounters(settings);
  }

  /**
   * Decides a classic call such as {@code log.info("x {}", y)}. A call with no message, arguments
   * or exception is Logback asking whether the level is on, so it is not counted; neither is a line
   * below the logger's level, which Logback drops anyway.
   *
   * @param marker the line's first marker, unused
   * @param logger the logger
   * @param level the line's level
   * @param format the message, or {@code null} for a level check
   * @param params the arguments
   * @param throwable the exception
   * @return {@code DENY} to drop the line, {@code NEUTRAL} to let Logback decide
   */
  @Override
  public FilterReply decide(
      @Nullable Marker marker,
      Logger logger,
      Level level,
      @Nullable String format,
      Object @Nullable [] params,
      @Nullable Throwable throwable) {
    if (format == null && params == null && throwable == null) {
      return FilterReply.NEUTRAL;
    }
    if (level.levelInt < logger.getEffectiveLevel().levelInt) {
      return FilterReply.NEUTRAL;
    }
    return decideLine(logger, level);
  }

  /**
   * Decides a call of SLF4J's fluent API, such as {@code log.atInfo().log("x")}, which Logback
   * writes without checking the level again, so every such call is counted.
   *
   * @param logger the logger
   * @param line the line
   * @return {@code DENY} to drop the line, {@code NEUTRAL} to let it be written
   */
  @Override
  public FilterReply decide(Logger logger, LoggingEvent line) {
    return decideLine(logger, Level.convertAnSLF4JLevel(line.getLevel()));
  }

  /** Writes the summary when due, then samples and counts the line. */
  private FilterReply decideLine(Logger logger, Level level) {
    var second = clock.millis() / MILLIS_PER_SECOND;
    writeSummaryWhenDue(logger.getLoggerContext(), second);
    var count = counters.countOf(logger, level);
    if (count.isNeverLimited()) {
      return FilterReply.NEUTRAL;
    }
    return isSampled(level) && count.keep(second) ? FilterReply.NEUTRAL : FilterReply.DENY;
  }

  /**
   * Returns whether sampling keeps a line: by its trace ID, so that a request's lines are kept or
   * dropped together, else by a random draw. WARN and ERROR are always kept.
   */
  private boolean isSampled(Level level) {
    var keepOneIn = keepOneIn(level);
    if (keepOneIn == KEEP_ALL) {
      return true;
    }
    var traceId = MDC.get(LoggingContextConstants.TRACE_ID);
    return traceId == null
        ? untracedDraw.nextInt(keepOneIn) == 0
        : (traceId.hashCode() & Integer.MAX_VALUE) % keepOneIn == 0;
  }

  /** Returns one in how many lines of a level sampling keeps. */
  private int keepOneIn(Level level) {
    if (level.isGreaterOrEqual(Level.WARN)) {
      return KEEP_ALL;
    }
    return level == Level.INFO ? settings.infoKeepOneIn() : settings.debugKeepOneIn();
  }

  /**
   * Writes the summary of dropped lines once per interval, from the one thread that claims it, with
   * an empty MDC so that it carries no field of the request that happened to trigger it.
   */
  private void writeSummaryWhenDue(LoggerContext context, long second) {
    var due = nextSummary.get();
    if (second < due
        || !nextSummary.compareAndSet(due, second + settings.reportInterval().toSeconds())
        || due == 0) {
      return;
    }
    var dropped = counters.takeDropped(second);
    if (dropped.lines() == 0) {
      return;
    }
    var requestFields = MDC.getCopyOfContextMap();
    MDC.clear();
    try {
      context
          .getLogger(SUMMARY_LOGGER)
          .atWarn()
          .addKeyValue(LoggingConstants.DROPPED_LINES, dropped.lines())
          .addKeyValue(LoggingConstants.DROPPED_KEYS, dropped.keys())
          .addKeyValue(LoggingConstants.TOP_DROPPED, String.join(", ", dropped.top()))
          .log(SUMMARY);
    } finally {
      if (requestFields != null) {
        MDC.setContextMap(requestFields);
      }
    }
  }
}
