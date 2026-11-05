package com.exepex.stratum.web;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.core.properties.BackwardCompatibleMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.format.annotation.DurationFormat;
import org.springframework.format.datetime.standard.DurationFormatterUtils;
import org.springframework.util.unit.DataSize;

/**
 * Tells the team at startup, outside backward-compatible mode, which time and body size limits a
 * request gets when no route or annotation sets one, and how an existing service keeps its old
 * behaviour.
 */
@StratumInternal
@Slf4j
@RequiredArgsConstructor
public class StandardModeReport implements SmartInitializingSingleton {

  /** What a limit of 0 or less, which means no limit, is shown as. */
  private static final String NO_LIMIT = "none";

  /** Bytes in a kilobyte, the unit a whole number of kilobytes is shown in. */
  private static final long BYTES_PER_KILOBYTE = DataSize.ofKilobytes(1).toBytes();

  /** Where the mode is read from. */
  private final Environment environment;

  /** The time limits, whose default the line shows. */
  private final ResponseTimeouts responseTimeouts;

  /** The body size limits, whose default the line shows. */
  private final RequestBodyLimits requestBodyLimits;

  /** Logs the INFO line, in standard mode only. */
  @Override
  public void afterSingletonsInstantiated() {
    if (!BackwardCompatibleMode.isOn(environment) && log.isInfoEnabled()) {
      var line =
          ("Standard mode: time limit %s, body size limit %s, errors as Problem Details. For an"
                  + " existing service, set %s=true.")
              .formatted(
                  timeLimitText(), bodyLimitText(), StratumModuleConstants.BACKWARD_COMPATIBLE);
      // The limits are digits and units; this also satisfies SpotBugs.
      log.info(line.replaceAll("[\r\n]", " "));
    }
  }

  /** Returns the default time limit as the team writes it, such as {@code 10s}. */
  private String timeLimitText() {
    var limit = responseTimeouts.getDefaultLimit();
    return limit.isPositive()
        ? DurationFormatterUtils.print(limit, DurationFormat.Style.COMPOSITE)
        : NO_LIMIT;
  }

  /** Returns the default body size limit in whole kilobytes, such as {@code 256KB}, else bytes. */
  private String bodyLimitText() {
    var bytes = requestBodyLimits.getDefaultLimit().toBytes();
    if (bytes <= 0) {
      return NO_LIMIT;
    }
    return bytes % BYTES_PER_KILOBYTE == 0 ? bytes / BYTES_PER_KILOBYTE + "KB" : bytes + "B";
  }
}
