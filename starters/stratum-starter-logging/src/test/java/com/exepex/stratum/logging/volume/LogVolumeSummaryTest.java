package com.exepex.stratum.logging.volume;

import static com.exepex.stratum.logging.testsupport.LogVolumeTestData.ORDERS;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.exepex.stratum.core.logging.LoggingContextConstants;
import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.testsupport.LogVolumeLines;
import com.exepex.stratum.logging.testsupport.LogVolumeTestData;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("LogVolumeFilter writes one summary of the dropped lines per interval")
class LogVolumeSummaryTest {

  /** The summary interval, in seconds. */
  private static final int INTERVAL = 10;

  /** How many loggers drop lines in the top-five test. */
  private static final int DROPPING_LOGGERS = 7;

  /** The lines the first test writes in one second, with a limit of one. */
  private static final int BUSY_SECOND_LINES = 5;

  /** The lines the top-five test drops: logger n writes n + 1 lines, of which one is kept. */
  private static final long TOP_FIVE_DROPS = 28;

  /** The last interval the no-summary test reaches. */
  private static final int LAST_INTERVAL = 3;

  /** Lines with a limit of one per second and a summary every ten seconds. */
  private final LogVolumeLines lines =
      new LogVolumeLines(
          LogVolumeTestData.defaults()
              .maxPerSecond(1)
              .reportInterval(Duration.ofSeconds(INTERVAL))
              .build());

  /** Returns the summaries written so far. */
  private List<ILoggingEvent> summaries() {
    return lines.lines().stream()
        .filter(line -> LogVolumeFilter.SUMMARY.equals(line.getMessage()))
        .toList();
  }

  /** Returns the fields of the only summary written. */
  private Map<String, Object> onlySummaryFields() {
    assertThat(summaries()).hasSize(1);
    return summaries().getFirst().getKeyValuePairs().stream()
        .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
  }

  /** Leaves no MDC field to the next test. */
  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  @DisplayName("a second that ended with no later line is still counted in the next summary")
  void countsTheLastBusySecond() {
    lines.writeInfo(ORDERS, BUSY_SECOND_LINES);
    lines.atSecond(INTERVAL);
    lines.writeInfo(LogVolumeTestData.OTHER, 1);

    assertThat(onlySummaryFields())
        .containsEntry(LoggingConstants.DROPPED_LINES, (long) BUSY_SECOND_LINES - 1)
        .containsEntry(LoggingConstants.DROPPED_KEYS, 1)
        .containsEntry(LoggingConstants.TOP_DROPPED, ORDERS + " INFO=" + (BUSY_SECOND_LINES - 1));
  }

  @Test
  @DisplayName("drops are counted once, even when the same logger writes again after a summary")
  void dropsAreCountedOnce() {
    lines.writeInfo(ORDERS, BUSY_SECOND_LINES);
    lines.atSecond(INTERVAL);
    lines.writeInfo(LogVolumeTestData.OTHER, 1);
    lines.writeInfo(ORDERS, 1);
    lines.atSecond(2 * INTERVAL);
    lines.writeInfo(LogVolumeTestData.OTHER, 1);

    assertThat(onlySummaryFields())
        .containsEntry(LoggingConstants.DROPPED_LINES, (long) BUSY_SECOND_LINES - 1);
  }

  @Test
  @DisplayName("the summary names the five loggers and levels that dropped the most")
  void namesTheTopFive() {
    IntStream.rangeClosed(1, DROPPING_LOGGERS)
        .forEach(logger -> lines.writeInfo(ORDERS + logger, logger + 1));
    lines.atSecond(INTERVAL);
    lines.writeInfo(LogVolumeTestData.OTHER, 1);

    assertThat(onlySummaryFields())
        .containsEntry(LoggingConstants.DROPPED_LINES, TOP_FIVE_DROPS)
        .containsEntry(LoggingConstants.DROPPED_KEYS, DROPPING_LOGGERS)
        .containsEntry(
            LoggingConstants.TOP_DROPPED,
            ORDERS
                + "7 INFO=7, "
                + ORDERS
                + "6 INFO=6, "
                + ORDERS
                + "5 INFO=5, "
                + ORDERS
                + "4 INFO=4, "
                + ORDERS
                + "3 INFO=3");
  }

  @Test
  @DisplayName("no summary is written before the interval ends, or when nothing was dropped")
  void noSummaryWithoutDropsOrBeforeTheInterval() {
    lines.writeInfo(ORDERS, BUSY_SECOND_LINES);
    lines.atSecond(INTERVAL - 1);
    lines.writeInfo(ORDERS, 1);
    lines.atSecond(2 * INTERVAL);
    lines.writeInfo(ORDERS, 1);
    lines.atSecond(LAST_INTERVAL * INTERVAL);
    lines.writeInfo(ORDERS, 1);

    assertThat(summaries()).hasSize(1);
  }

  @Test
  @DisplayName("the summary carries no field of the request that triggered it, which keeps them")
  void summaryHasNoRequestFields() {
    lines.writeInfo(ORDERS, 2);
    lines.atSecond(INTERVAL);
    MDC.put(LoggingContextConstants.TRACE_ID, LogVolumeTestData.KEPT_TRACE_ID);
    lines.writeInfo(LogVolumeTestData.OTHER, 1);

    assertThat(summaries().getFirst().getMDCPropertyMap()).isEmpty();
    assertThat(MDC.get(LoggingContextConstants.TRACE_ID))
        .isEqualTo(LogVolumeTestData.KEPT_TRACE_ID);
  }
}
