package com.exepex.stratum.logging.volume;

import static com.exepex.stratum.logging.testsupport.LogVolumeTestData.ORDERS;
import static com.exepex.stratum.logging.testsupport.LogVolumeTestData.RARELY;
import static com.exepex.stratum.logging.testsupport.LogVolumeTestData.UNTRACED_LINES;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.exepex.stratum.core.logging.LoggingContextConstants;
import com.exepex.stratum.logging.testsupport.LogVolumeLines;
import com.exepex.stratum.logging.testsupport.LogVolumeTestData;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("LogVolumeFilter samples DEBUG and INFO lines by trace ID, else at random")
class LogVolumeSamplingTest {

  /** The levels each round of lines writes: DEBUG, INFO, WARN and ERROR. */
  private static final int LEVELS = 4;

  /** Writes the same number of lines at each level, from one logger. */
  private void writeEachLevel(LogVolumeLines lines) {
    var logger = lines.logger(ORDERS);
    IntStream.range(0, UNTRACED_LINES).forEach(line -> writeOneOfEach(logger, line));
  }

  /** Writes one line at each level. */
  private void writeOneOfEach(Logger logger, int line) {
    logger.debug("debug {}", line);
    logger.info("info {}", line);
    logger.warn("warn {}", line);
    logger.error("error {}", line);
  }

  /** Leaves no trace ID to the next test. */
  @AfterEach
  void clearTraceId() {
    MDC.clear();
  }

  @Test
  @DisplayName("with no sampling set, every line is kept")
  void keepsEveryLineByDefault() {
    var lines = new LogVolumeLines(LogVolumeTestData.defaults().build());

    writeEachLevel(lines);

    assertThat(lines.lines()).hasSize(LEVELS * UNTRACED_LINES);
  }

  @Test
  @DisplayName("untraced DEBUG lines are sampled at random, while INFO, WARN and ERROR are kept")
  void untracedDebugLinesAreSampledAtRandom() {
    var lines = new LogVolumeLines(LogVolumeTestData.defaults().debugKeepOneIn(2).build());

    writeEachLevel(lines);

    assertThat(lines.count(Level.DEBUG)).isStrictlyBetween(0L, (long) UNTRACED_LINES);
    assertThat(lines.count(Level.INFO)).isEqualTo(UNTRACED_LINES);
    assertThat(lines.count(Level.WARN)).isEqualTo(UNTRACED_LINES);
    assertThat(lines.count(Level.ERROR)).isEqualTo(UNTRACED_LINES);
  }

  @Test
  @DisplayName("untraced INFO lines use info-keep-one-in, and WARN is never sampled")
  void untracedInfoLinesAreSampledAtRandom() {
    var lines = new LogVolumeLines(LogVolumeTestData.defaults().infoKeepOneIn(RARELY).build());

    writeEachLevel(lines);

    assertThat(lines.count(Level.INFO)).isLessThan(UNTRACED_LINES);
    assertThat(lines.count(Level.DEBUG)).isEqualTo(UNTRACED_LINES);
    assertThat(lines.count(Level.WARN)).isEqualTo(UNTRACED_LINES);
  }

  @Test
  @DisplayName("every line of a request is kept or dropped together, by its trace ID")
  void tracedLinesFollowTheirTrace() {
    var lines =
        new LogVolumeLines(LogVolumeTestData.defaults().debugKeepOneIn(2).infoKeepOneIn(2).build());

    MDC.put(LoggingContextConstants.TRACE_ID, LogVolumeTestData.KEPT_TRACE_ID);
    writeEachLevel(lines);
    MDC.put(LoggingContextConstants.TRACE_ID, LogVolumeTestData.DROPPED_TRACE_ID);
    writeEachLevel(lines);

    assertThat(lines.count(Level.DEBUG)).isEqualTo(UNTRACED_LINES);
    assertThat(lines.count(Level.INFO)).isEqualTo(UNTRACED_LINES);
    assertThat(lines.lines())
        .filteredOn(line -> !line.getLevel().isGreaterOrEqual(Level.WARN))
        .allSatisfy(
            line ->
                assertThat(line.getMDCPropertyMap())
                    .containsEntry(
                        LoggingContextConstants.TRACE_ID, LogVolumeTestData.KEPT_TRACE_ID));
  }
}
