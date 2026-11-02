package com.exepex.stratum.logging.redaction;

import static com.exepex.stratum.logging.testsupport.LoggingTestData.CARD;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.DEFAULT_REDACTOR;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.EMAIL;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.logging.StandardStackTracePrinter;

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

    @ParameterizedTest
    @FieldSource("SKIPPED_LINES")
    @DisplayName("is written as it is")
    void lineIsKept(String line) {
      var stackTrace = "java.lang.IllegalStateException: failed\n" + line + "\n";

      assertThat(redactStackTrace(stackTrace)).isEqualTo(stackTrace);
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
}
