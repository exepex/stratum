package com.exepex.stratum.logging.redaction;

import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;

/**
 * Masks a stack trace without searching its frame lines. Only a line that is wholly a frame, such
 * as {@code at com.example.Orders.place(Orders.java:42)}, or an omission, such as {@code ... 12
 * more}, is written as it is; every other line, such as the exception's message, {@code Caused by:}
 * or {@code Suppressed:}, is masked by the content rules.
 */
@RequiredArgsConstructor
final class StackTraceMasker {

  // Every part is a fixed shape, matched possessively and with no repeated group, so a line with
  // text after a frame, or a frame made of digits, is not skipped, and matching stays linear.
  /** A Java identifier. */
  private static final String IDENTIFIER = "[A-Za-z_$][\\w$]*+";

  /**
   * A class and method, such as {@code java.base@25/com.example.Orders.<init>}: no part after a dot
   * or slash starts with a digit, and an {@code @} only starts a module version.
   */
  private static final String CLASS_AND_METHOD =
      "(?![^(]*[./]\\d)(?![^(]*@[^\\d])[A-Za-z_$][\\w$.<>/@]*+";

  /** A line number or a count of omitted frames. */
  private static final String SMALL_NUMBER = "\\d{1,6}";

  /** A source file and line, or where the JVM has none. */
  private static final String SOURCE =
      "(?:"
          + IDENTIFIER
          + "\\.(?:java|kt|scala|groovy)|"
          + IDENTIFIER
          + "\\.(?:java|kt|scala|groovy):"
          + SMALL_NUMBER
          + "|SourceFile|SourceFile:"
          + SMALL_NUMBER
          + "|<generated>|Native Method|Unknown Source)";

  /** The end of a frame line: Logback's optional jar suffix, such as {@code ~[x.jar:1.0]}. */
  private static final String LINE_END = "(?:\\r?\\n?| ~?\\[[\\w.$?-]*+:[\\w.?-]*+\\]\\r?\\n?)";

  /** A line that is wholly a frame. */
  private static final Pattern FRAME_LINE =
      Pattern.compile("[ \\t]*+at " + CLASS_AND_METHOD + "\\(" + SOURCE + "\\)" + LINE_END);

  /** Frames left out: common to the enclosing trace, or filtered. */
  private static final Pattern OMITTED_LINE =
      Pattern.compile(
          "[ \\t]*+\\.\\.\\. " + SMALL_NUMBER + " (?:more|filtered|common frames omitted)\\r?\\n?");

  /** The place after each line break, where the trace is split into lines. */
  private static final Pattern AFTER_LINE_BREAK = Pattern.compile("(?<=\\n)");

  /** Masks text by what it looks like. */
  private final UnaryOperator<String> contentMaskingRules;

  /**
   * Masks every line of the stack trace except its frame and omission lines.
   *
   * @param stackTrace the stack trace, as Spring Boot or Logback prints it
   * @return the masked stack trace
   */
  String mask(String stackTrace) {
    var masked = new StringBuilder(stackTrace.length());
    var searched = new StringBuilder();
    // A loop, because the lines between two frames are masked together, as one text.
    for (var line : AFTER_LINE_BREAK.split(stackTrace)) {
      if (isFrameOrOmission(line)) {
        masked.append(contentMaskingRules.apply(searched.toString())).append(line);
        searched.setLength(0);
      } else {
        searched.append(line);
      }
    }
    return masked.append(contentMaskingRules.apply(searched.toString())).toString();
  }

  /** Returns whether the whole line is a frame or an omission. */
  private boolean isFrameOrOmission(String line) {
    return FRAME_LINE.matcher(line).matches() || OMITTED_LINE.matcher(line).matches();
  }
}
