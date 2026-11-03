package com.exepex.stratum.logging.volume;

import static com.exepex.stratum.logging.testsupport.LogVolumeTestData.ORDERS;
import static com.exepex.stratum.logging.testsupport.LogVolumeTestData.SMALL_LIMIT;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.exepex.stratum.logging.testsupport.LogVolumeLines;
import com.exepex.stratum.logging.testsupport.LogVolumeTestData;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("LogVolumeFilter keeps each logger and level within its limit per second")
class LogVolumeFilterTest {

  /** Lines with the default settings and a limit of three per second at every level. */
  private final LogVolumeLines lines =
      new LogVolumeLines(
          LogVolumeTestData.defaults()
              .maxPerSecond(SMALL_LIMIT)
              .warnErrorMaxPerSecond(SMALL_LIMIT)
              .build());

  @Nested
  @DisplayName("within one second")
  class Limit {

    @Test
    @DisplayName("the lines up to the limit are written and the rest are dropped")
    void keepsTheLimitThenDrops() {
      lines.writeInfo(ORDERS, SMALL_LIMIT + 2);

      assertThat(lines.lines()).hasSize(SMALL_LIMIT);
    }

    @Test
    @DisplayName("the next second starts a new count")
    void nextSecondCountsAgain() {
      lines.writeInfo(ORDERS, SMALL_LIMIT + 1);
      lines.atSecond(1);
      lines.writeInfo(ORDERS, SMALL_LIMIT + 1);

      assertThat(lines.lines()).hasSize(2 * SMALL_LIMIT);
    }

    @Test
    @DisplayName("a caller whose clock is a second behind counts in the newer second")
    void staleCallerDoesNotResetTheSecond() {
      lines.atSecond(1);
      lines.writeInfo(ORDERS, SMALL_LIMIT);
      lines.atSecond(0);
      lines.writeInfo(ORDERS, 1);

      assertThat(lines.lines()).hasSize(SMALL_LIMIT);
    }

    @Test
    @DisplayName("WARN and ERROR use their own, separate limit")
    void warnAndErrorHaveTheirOwnLimit() {
      var higherWarnLimit =
          new LogVolumeLines(
              LogVolumeTestData.defaults()
                  .maxPerSecond(1)
                  .warnErrorMaxPerSecond(SMALL_LIMIT)
                  .build());
      var logger = higherWarnLimit.logger(ORDERS);
      IntStream.range(0, SMALL_LIMIT + 1).forEach(line -> logger.warn("warn {}", line));
      IntStream.range(0, SMALL_LIMIT + 1).forEach(line -> logger.error("error {}", line));
      higherWarnLimit.writeInfo(ORDERS, SMALL_LIMIT);

      assertThat(higherWarnLimit.count(Level.WARN)).isEqualTo(SMALL_LIMIT);
      assertThat(higherWarnLimit.count(Level.ERROR)).isEqualTo(SMALL_LIMIT);
      assertThat(higherWarnLimit.count(Level.INFO)).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("for calls that are not lines")
  class NotCounted {

    @Test
    @DisplayName("a check whether a level is on is not counted")
    void levelChecksAreNotCounted() {
      var logger = lines.logger(ORDERS);
      IntStream.range(0, SMALL_LIMIT + 1).forEach(check -> logger.isInfoEnabled());
      lines.writeInfo(ORDERS, 1);

      assertThat(lines.lines()).hasSize(1);
    }

    @Test
    @DisplayName(
        "a line below the logger's level is not counted, so a lower level lets the next in")
    void linesBelowTheLevelAreNotCounted() {
      var logger = lines.logger(ORDERS);
      logger.setLevel(Level.INFO);
      IntStream.range(0, SMALL_LIMIT + 1).forEach(line -> logger.debug("hidden {}", line));
      logger.setLevel(Level.DEBUG);
      logger.debug("shown");

      assertThat(lines.lines()).extracting("message").containsExactly("shown");
    }
  }

  @Nested
  @DisplayName("for lines with no message")
  class Counted {

    @Test
    @DisplayName("a fluent line with only fields is counted")
    void fluentLineWithoutMessageIsCounted() {
      var logger = lines.logger(ORDERS);
      IntStream.range(0, SMALL_LIMIT + 1)
          .forEach(line -> logger.atInfo().addKeyValue("orderId", line).log());

      assertThat(lines.lines()).hasSize(SMALL_LIMIT);
    }

    @Test
    @DisplayName("an error logged with an exception and no message is counted")
    void errorWithoutMessageIsCounted() {
      var logger = lines.logger(ORDERS);
      var failure = new IllegalStateException();
      IntStream.range(0, SMALL_LIMIT + 1).forEach(line -> logger.error(null, failure));

      assertThat(lines.lines()).hasSize(SMALL_LIMIT);
    }

    @Test
    @DisplayName("a line with arguments and no message is counted")
    void lineWithArgumentsOnlyIsCounted() {
      var logger = lines.logger(ORDERS);
      IntStream.range(0, SMALL_LIMIT + 1).forEach(line -> logger.info(null, line));

      assertThat(lines.lines()).hasSize(SMALL_LIMIT);
    }
  }

  @Nested
  @DisplayName("with many threads at once")
  class Concurrency {

    /** The threads that write at once. */
    private static final int THREADS = 8;

    /** The lines each thread writes, all in the same second. */
    private static final int LINES_PER_THREAD = 2000;

    /** The loggers the threads write to for the first time, all at once. */
    private static final int NEW_LOGGERS = 64;

    @Test
    @DisplayName("lines written at once from many threads keep exactly the limit")
    void concurrentLinesKeepExactlyTheLimit() {
      writeAtOnce(thread -> lines.writeInfo(ORDERS, LINES_PER_THREAD));

      assertThat(lines.lines()).hasSize(SMALL_LIMIT);
    }

    @Test
    @DisplayName("new loggers first written from many threads at once each get one count")
    void concurrentNewLoggersGetOneCountEach() {
      writeAtOnce(thread -> IntStream.range(0, NEW_LOGGERS).forEach(this::writeToNewLogger));

      assertThat(lines.lines()).hasSize(NEW_LOGGERS * SMALL_LIMIT);
    }

    /** Writes, from every thread at once, what the work writes. */
    private void writeAtOnce(IntConsumer work) {
      var start = new CountDownLatch(1);
      try (var threads = Executors.newFixedThreadPool(THREADS)) {
        IntStream.range(0, THREADS)
            .forEach(thread -> threads.execute(() -> runAfter(start, () -> work.accept(thread))));
        start.countDown();
      }
    }

    /** Writes one line to one of the new loggers. */
    private void writeToNewLogger(int logger) {
      lines.writeInfo(ORDERS + ".new" + logger, 1);
    }

    /** Waits for the start, then runs the work, so that every thread runs at once. */
    private void runAfter(CountDownLatch start, Runnable work) {
      try {
        start.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
      work.run();
    }
  }

  @Nested
  @DisplayName("for the never-limited loggers")
  class NeverLimited {

    @Test
    @DisplayName("Spring Security's loggers are never limited by default")
    void securityLoggersAreNotLimited() {
      lines.writeInfo(LogVolumeTestData.SECURITY_ROOT, SMALL_LIMIT + 2);
      lines.writeInfo(LogVolumeTestData.SECURITY, SMALL_LIMIT + 2);

      assertThat(lines.lines()).hasSize(2 * (SMALL_LIMIT + 2));
    }

    @Test
    @DisplayName("a logger whose name only starts the same way is limited")
    void lookalikeLoggerIsLimited() {
      lines.writeInfo(LogVolumeTestData.SECURITY_LOOKALIKE, SMALL_LIMIT + 2);

      assertThat(lines.lines()).hasSize(SMALL_LIMIT);
    }
  }

  @Nested
  @DisplayName("with more loggers than max-keys")
  class BoundedKeys {

    /** The fewest keys counted on their own. */
    private static final int MIN_KEYS = 64;

    /** Ten times as many loggers as have a count of their own. */
    private static final int MANY_LOGGERS = 640;

    /** The lines of loggers with their own count and the shared count, with a limit of one. */
    private final LogVolumeLines fewKeys =
        new LogVolumeLines(
            LogVolumeTestData.defaults()
                .maxKeys(MIN_KEYS)
                .maxPerSecond(1)
                .warnErrorMaxPerSecond(1)
                .build());

    @Test
    @DisplayName("the loggers past max-keys share one count, which keeps the limit")
    void loggersPastMaxKeysShareTheLimit() {
      IntStream.range(0, MANY_LOGGERS).forEach(logger -> fewKeys.writeInfo(ORDERS + logger, 1));

      assertThat(fewKeys.lines()).hasSize(MIN_KEYS + 1);
    }

    @Test
    @DisplayName("an INFO flood in the shared count leaves WARN lines their own")
    void sharedCountIsPerLevel() {
      IntStream.range(0, MIN_KEYS * 2).forEach(logger -> fewKeys.writeInfo(ORDERS + logger, 1));
      fewKeys.logger(ORDERS + "-late").warn("late warning");

      assertThat(fewKeys.count(Level.WARN)).isEqualTo(1);
    }

    @Test
    @DisplayName("a never-limited logger past max-keys is still never limited")
    void neverLimitedLoggerPastMaxKeysIsNotLimited() {
      IntStream.range(0, MIN_KEYS * 2).forEach(logger -> fewKeys.writeInfo(ORDERS + logger, 1));
      fewKeys.writeInfo(LogVolumeTestData.SECURITY, SMALL_LIMIT);

      assertThat(fewKeys.lines())
          .filteredOn(line -> LogVolumeTestData.SECURITY.equals(line.getLoggerName()))
          .hasSize(SMALL_LIMIT);
    }
  }
}
