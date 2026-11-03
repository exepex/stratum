package com.exepex.stratum.logging.volume;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.logging.LoggingProperties.Volume;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Counts the lines of each logger and level in one-second windows, in a table of fixed size that
 * never grows: once {@code max-keys} loggers and levels have their own count, any more share one
 * count per level.
 */
@StratumInternal
public class LogVolumeCounters {

  /** Table slots per allowed key, so that a key nearly always finds a free slot near its hash. */
  private static final int SLOTS_PER_KEY = 4;

  /** The most slots a key looks at before it shares its level's count. */
  private static final int MAX_PROBES = 16;

  /** How many of the loggers and levels that dropped the most lines the summary names. */
  private static final int TOP_DROPPED = 5;

  /** The name the summary gives the loggers that share their level's count. */
  private static final String SHARED_LOGGERS = "other loggers";

  /** Logback's levels in order, so that a level's position picks its shared count. */
  private static final List<Level> LEVELS =
      List.of(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR);

  /** The counts of the keys, each in the first free slot from its hash. */
  private final AtomicReferenceArray<LineCount> table;

  /** How many keys have a count of their own. */
  private final AtomicInteger keys = new AtomicInteger();

  /** The most keys with a count of their own. */
  private final int maxKeys;

  /** The settings that give each level its limit and each logger its exemption. */
  private final Volume settings;

  /** The logger of the summary of drops, never limited. */
  private final String summaryLogger;

  /** One shared count per level, for the keys that found no slot. */
  private final List<LineCount> sharedCounts;

  /** The count of every line of a never-limited logger that found no slot of its own. */
  private final LineCount neverLimited;

  /**
   * Creates an empty table.
   *
   * @param settings the limits, the never-limited loggers and the most keys
   * @param summaryLogger the logger the summary of drops is written to, which is never limited, so
   *     that no other line can use up its count and lose a summary
   */
  public LogVolumeCounters(Volume settings, String summaryLogger) {
    this.settings = settings;
    this.summaryLogger = summaryLogger;
    maxKeys = settings.maxKeys();
    table = new AtomicReferenceArray<>(Integer.highestOneBit(maxKeys * SLOTS_PER_KEY - 1) << 1);
    sharedCounts =
        LEVELS.stream()
            .map(level -> new LineCount(null, SHARED_LOGGERS, level, limitOf(level), false))
            .toList();
    neverLimited = new LineCount(null, SHARED_LOGGERS, Level.ERROR, Integer.MAX_VALUE, true);
  }

  /**
   * Returns the count of a logger and level: its own while the table has room, else its level's
   * shared one.
   *
   * @param logger the logger the line is written to
   * @param level the line's level
   * @return the count
   */
  public LineCount countOf(Logger logger, Level level) {
    var mask = table.length() - 1;
    var hash = System.identityHashCode(logger) + level.levelInt;
    var start = hash ^ (hash >>> Short.SIZE);
    // A loop, not a stream: this runs on every log call and must not allocate.
    for (var probe = 0; probe < MAX_PROBES; probe++) {
      var index = (start + probe) & mask;
      var count = table.get(index);
      if (count == null) {
        count = claim(index, logger, level);
      }
      if (count == null) {
        break;
      }
      if (logger.equals(count.logger) && level.equals(count.level)) {
        return count;
      }
    }
    return isNeverLimited(logger.getName())
        ? neverLimited
        : sharedCounts.get(LEVELS.indexOf(level));
  }

  /**
   * Returns the lines dropped over the limits until this second, and starts counting them again.
   * Windows that ended without another line are closed here, so their drops are counted too.
   *
   * @param second the current second, in seconds since the epoch
   * @return the dropped lines
   */
  public DroppedLines takeDropped(long second) {
    var dropped =
        Stream.concat(
                IntStream.range(0, table.length()).mapToObj(table::get), sharedCounts.stream())
            .filter(Objects::nonNull)
            .map(count -> new KeyDrops(count, count.takeDropped(second)))
            .filter(keyDrops -> keyDrops.lines() > 0)
            .toList();
    var top =
        dropped.stream()
            .sorted(Comparator.comparingLong(KeyDrops::lines).reversed())
            .limit(TOP_DROPPED)
            .map(keyDrops -> keyDrops.count().describe() + "=" + keyDrops.lines())
            .toList();
    return new DroppedLines(dropped.stream().mapToLong(KeyDrops::lines).sum(), dropped.size(), top);
  }

  /**
   * Puts a new count for the key into a free slot, or returns the count another thread put there
   * first; returns {@code null} when {@code max-keys} keys already have their own.
   */
  private LineCount claim(int index, Logger logger, Level level) {
    if (keys.incrementAndGet() > maxKeys) {
      keys.decrementAndGet();
      return null;
    }
    var name = logger.getName();
    var fresh = new LineCount(logger, name, level, limitOf(level), isNeverLimited(name));
    if (table.compareAndSet(index, null, fresh)) {
      return fresh;
    }
    keys.decrementAndGet();
    return table.get(index);
  }

  /** Returns the most lines per second a level allows. */
  private int limitOf(Level level) {
    return level.isGreaterOrEqual(Level.WARN)
        ? settings.warnErrorMaxPerSecond()
        : settings.maxPerSecond();
  }

  /** Returns whether a logger is never limited: the summary's, listed, or below a listed logger. */
  private boolean isNeverLimited(String name) {
    return name.equals(summaryLogger)
        || settings.neverLimitLoggers().stream()
            .anyMatch(listed -> name.equals(listed) || name.startsWith(listed + "."));
  }

  /**
   * The lines one logger and level wrote in the current second. One word holds the second and the
   * count, so that a line takes one atomic step; a window only ever moves forward.
   */
  @StratumInternal
  @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
  public static final class LineCount {

    /** The bits of the word that hold the count; the second is in the bits above. */
    private static final long COUNT_BITS = 0xFFFF_FFFFL;

    /** The logger, compared by identity; {@code null} for a shared count. */
    private final @Nullable Logger logger;

    /** The logger's name, for the summary. */
    private final String loggerName;

    /** The level counted. */
    private final Level level;

    /** The most lines per second. */
    private final int limit;

    /** Whether the logger's lines are never limited or sampled. */
    @Getter private final boolean neverLimited;

    /** The current second and the lines counted in it: {@code second << 32 | count}. */
    private final AtomicLong window = new AtomicLong();

    /** The lines dropped in windows that have ended, not yet in a summary. */
    private final AtomicLong dropped = new AtomicLong();

    /**
     * Counts a line and returns whether it is within this second's limit. A caller whose clock is
     * behind counts in the newer window and never moves it back.
     *
     * @param second the caller's second, in seconds since the epoch
     * @return whether the line is kept
     */
    public boolean keep(long second) {
      // A loop, not a stream: it retries only when another thread moved the window meanwhile.
      while (true) {
        var current = window.get();
        if (current >>> Integer.SIZE >= second) {
          return (window.getAndIncrement() & COUNT_BITS) < limit;
        }
        if (window.compareAndSet(current, second << Integer.SIZE | 1)) {
          addDropped(current);
          return true;
        }
      }
    }

    /**
     * Closes a window that is older than the second and went over its limit, and returns the drops
     * since the last call. A window within its limit is only read.
     */
    private long takeDropped(long second) {
      var current = window.get();
      while (current >>> Integer.SIZE < second
          && (current & COUNT_BITS) > limit
          && !window.compareAndSet(current, second << Integer.SIZE)) {
        current = window.get();
      }
      if (current >>> Integer.SIZE < second) {
        addDropped(current);
      }
      // Most counts dropped nothing: reading first spares a write to each of them.
      return dropped.get() == 0 ? 0 : dropped.getAndSet(0);
    }

    /** Adds the lines of an ended window that were over the limit. */
    private void addDropped(long endedWindow) {
      var over = (endedWindow & COUNT_BITS) - limit;
      if (over > 0) {
        dropped.addAndGet(over);
      }
    }

    /** Returns the key as the summary names it, such as {@code com.acme.Orders INFO}. */
    private String describe() {
      return loggerName + " " + level;
    }
  }

  /**
   * The lines dropped over the limits since the last summary.
   *
   * @param lines how many lines were dropped
   * @param keys how many loggers and levels dropped lines
   * @param top the loggers and levels that dropped the most, as {@code logger LEVEL=count}
   */
  @StratumInternal
  public record DroppedLines(long lines, int keys, List<String> top) {

    /**
     * Keeps an unchangeable copy of the list.
     *
     * @param lines how many lines were dropped
     * @param keys how many loggers and levels dropped lines
     * @param top the loggers and levels that dropped the most
     */
    public DroppedLines {
      top = List.copyOf(top);
    }
  }

  /**
   * The drops of one key, before they are summed.
   *
   * @param count the count of the logger and level, named only if it is among the top five
   * @param lines how many of its lines were dropped
   */
  private record KeyDrops(LineCount count, long lines) {}
}
