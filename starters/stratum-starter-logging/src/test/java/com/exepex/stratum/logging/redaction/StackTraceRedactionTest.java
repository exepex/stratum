package com.exepex.stratum.logging.redaction;

import static com.exepex.stratum.logging.testsupport.LoggingTestData.CARD;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.DEFAULT_REDACTOR;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.EMAIL;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.logging.testsupport.LoggingTestData;
import com.exepex.stratum.redaction.MaskedText;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.logging.StandardStackTracePrinter;
import org.springframework.util.unit.DataSize;

@DisplayName("A stack trace is masked except its frame lines")
class StackTraceRedactionTest {

  /** Returns the masked stack trace as text. */
  private String redactStackTrace(String stackTrace) {
    return (String) DEFAULT_REDACTOR.redactStackTrace(null, stackTrace);
  }

  @Nested
  @DisplayName("as Spring Boot prints it")
  class PrintedByBoot {

    /** A failure whose message and cause hold personal data, printed root first as Stratum sets. */
    private final String printed =
        StandardStackTracePrinter.rootFirst()
            .printStackTraceToString(
                new IllegalStateException(
                    "order failed for " + EMAIL,
                    new IllegalArgumentException("card " + CARD + " declined")));

    @Test
    @DisplayName("the messages of the exception and its cause are masked")
    void messagesAreMasked() {
      assertThat(redactStackTrace(printed))
          .doesNotContain(EMAIL)
          .doesNotContain(CARD)
          .contains("order failed for ****.com")
          .contains("card ****1111 declined");
    }

    @Test
    @DisplayName("the frame lines are written exactly as they were")
    void framesAreKept() {
      var frames = printed.lines().filter(line -> line.strip().startsWith("at ")).toList();

      assertThat(frames).isNotEmpty();
      assertThat(redactStackTrace(printed).lines()).containsAll(frames);
    }
  }

  @Nested
  @DisplayName("a line that is wholly a frame or an omission")
  class SkippedLines {

    /** What the marking content rule writes in place of any text it is given. */
    private static final String SEARCHED = "<searched>";

    /** Frame and omission lines in the shapes Spring Boot and Logback print. */
    private static final List<String> SKIPPED_LINES =
        List.of(
            "\tat com.example.Orders.place(Orders.java:42)",
            "\tat com.example.Login.check(Password.java:12)",
            "\tat java.base/java.lang.Thread.run(Thread.java:1583)",
            "\tat java.base@25/java.lang.Thread.run(Thread.java:1583)",
            "\tat app//com.example.Orders.<init>(Orders.java:7)",
            "\tat com.example.Orders.lambda$place$0(Orders.kt:12)",
            "\tat jdk.internal.reflect.DirectMethodHandleAccessor.invoke(Unknown Source)",
            "\tat java.base/java.lang.Object.wait0(Native Method)",
            "\tat com.example.Proxy.call(<generated>)",
            "\tat com.example.Orders.place(Orders.java:42) ~[orders-1.0.jar:1.0]",
            "\tat com.example.Orders.place(Orders.java:42) [?:?]",
            "\t... 12 more",
            "\t\t... 3 filtered",
            "\t... 5 common frames omitted");

    /** Marks every text the content rules were given, so a test sees which lines were searched. */
    private final StackTraceMasker markingMasker =
        new StackTraceMasker(text -> text.isEmpty() ? text : SEARCHED);

    @ParameterizedTest
    @FieldSource("SKIPPED_LINES")
    @DisplayName("is not searched, and written as it is")
    void lineIsNotSearched(String line) {
      assertThat(markingMasker.mask("java.lang.IllegalStateException: failed\n" + line + "\n"))
          .isEqualTo(SEARCHED + line + "\n");
    }
  }

  @Nested
  @DisplayName("a line that only looks like a frame")
  class SearchedLines {

    /** Lines a message can hold that start like a frame but carry personal data. */
    private static final List<String> FAKE_FRAME_LINES =
        List.of(
            "\tat a.b(" + CARD + ")",
            "\tat a.b(C.java:" + CARD + ")",
            "\tat " + EMAIL + "/a.b(C.java:1)",
            "\t... " + CARD + " more",
            "\tat a.b(C.java:1) " + CARD,
            "\t... 1 more " + EMAIL);

    @ParameterizedTest
    @FieldSource("FAKE_FRAME_LINES")
    @DisplayName("is searched and masked")
    void lineIsMasked(String line) {
      var masked =
          redactStackTrace("java.lang.NumberFormatException: For input string: \"\n" + line);

      assertThat(masked).doesNotContain(CARD).doesNotContain(EMAIL);
    }

    @Test
    @DisplayName("the Caused by, Suppressed and Wrapped by lines are searched")
    void captionLinesAreMasked() {
      var stackTrace =
          """
          java.lang.IllegalStateException: failed
          \tat a.B.c(B.java:1)
          Caused by: java.io.IOException: %s
          \tSuppressed: java.io.IOException: %s
          Wrapped by: java.lang.RuntimeException: %s
          """
              .formatted(EMAIL, CARD, EMAIL);

      assertThat(redactStackTrace(stackTrace))
          .doesNotContain(EMAIL)
          .doesNotContain(CARD)
          .contains("\tat a.B.c(B.java:1)\n");
    }
  }

  @Nested
  @DisplayName("a value that is not text")
  class NotText {

    @Test
    @DisplayName("is masked as any other value, and nothing stays nothing")
    void otherValueIsMaskedByContent() {
      assertThat(DEFAULT_REDACTOR.redactStackTrace(null, new StringBuilder("card " + CARD)))
          .isEqualTo("card ****1111");
      assertThat(DEFAULT_REDACTOR.redactStackTrace(null, null)).isNull();
    }
  }

  @Nested
  @DisplayName("a stack trace longer than the maximum field length")
  class TooLong {

    /** The smallest allowed limit. */
    private static final DataSize SMALL_LIMIT = DataSize.ofKilobytes(1);

    /** Frame lines enough to pass the limit several times over. */
    private static final int FRAME_COUNT = 200;

    /** Masks with the smallest allowed limit. */
    private final Redactor smallLimitRedactor =
        Redactor.from(LoggingTestData.defaults().maxFieldLength(SMALL_LIMIT).build());

    @Test
    @DisplayName("is cut to the limit and ends with the truncation marker")
    void longStackTraceIsCut() {
      var stackTrace =
          "java.lang.IllegalStateException: failed\n"
              + "\tat a.B.c(B.java:1)\n".repeat(FRAME_COUNT);

      var redacted = (String) smallLimitRedactor.redactStackTrace(null, stackTrace);

      assertThat(redacted)
          .endsWith(Redactor.TRUNCATION_MARKER)
          .hasSizeLessThanOrEqualTo(
              (int) SMALL_LIMIT.toBytes() + Redactor.TRUNCATION_MARKER.length());
    }
  }

  @Nested
  @DisplayName("with the service's own patterns")
  class ServicePatterns {

    /** A trace whose only frame names the service's class. */
    private static final String STACK_TRACE =
        "java.lang.IllegalStateException: failed\n\tat com.example.Orders.place(Orders.java:42)\n";

    /** Returns a redactor with one of the service's own patterns. */
    private Redactor redactorWithPattern(String pattern) {
      return Redactor.from(LoggingTestData.withRedaction(List.of(), List.of(pattern)).build());
    }

    @Test
    @DisplayName("a pattern also masks frame lines")
    void patternMasksFrameLines() {
      assertThat(redactorWithPattern("com\\.example\\.Orders").redactStackTrace(null, STACK_TRACE))
          .isEqualTo("java.lang.IllegalStateException: failed\n\tat ****.place(Orders.java:42)\n");
    }

    @Test
    @DisplayName("a pattern that matches everything masks the whole trace")
    void patternMasksWholeTrace() {
      assertThat(redactorWithPattern("(?s).+").redactStackTrace(null, STACK_TRACE))
          .isEqualTo(MaskedText.MASK);
    }
  }
}
