package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import java.util.function.Supplier;
import lombok.experimental.UtilityClass;
import org.slf4j.Logger;

/** Keeps a text that goes into a log line or a startup message on one line. */
@StratumInternal
@UtilityClass
public class LogLines {

  /** The line breaks a logged text must not carry. */
  private static final String LINE_BREAKS = "[\r\n]";

  /**
   * Returns the text with each line break replaced by a space, so it cannot start a forged line.
   *
   * @param text the text, such as a pool name or a key
   * @return the text on one line
   */
  public static String oneLine(String text) {
    return text.replaceAll(LINE_BREAKS, " ");
  }

  /**
   * Logs a line at info level, on one line, building it only if info is on. Other modules log
   * through it, since SpotBugs sees the line breaks removed only inside this module.
   *
   * @param log the logger
   * @param line builds the line
   */
  public static void info(Logger log, Supplier<String> line) {
    if (log.isInfoEnabled()) {
      log.info(oneLine(line.get()));
    }
  }

  /**
   * Logs a line at debug level, on one line, building it only if debug is on.
   *
   * @param log the logger
   * @param line builds the line
   */
  public static void debug(Logger log, Supplier<String> line) {
    if (log.isDebugEnabled()) {
      log.debug(oneLine(line.get()));
    }
  }
}
