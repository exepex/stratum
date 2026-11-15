package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import lombok.experimental.UtilityClass;

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
}
