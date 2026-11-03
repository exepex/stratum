package com.exepex.stratum.logging.redaction;

import static com.exepex.stratum.logging.testsupport.LoggingTestData.CARD_IN_GROUPS;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.environmentWithModuleOnAndServiceName;
import static com.exepex.stratum.logging.testsupport.LoggingTestData.runLoggingPostProcessor;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.testsupport.LoggingTestData;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.boot.logging.StandardStackTracePrinter;
import org.springframework.util.unit.DataSize;

@DisplayName("A stack trace cut at the limit never shows part of a value")
class StackTraceCutRedactionTest {

  /** How far before or after the cut the card's first digit is placed, at most. */
  private static final int FARTHEST_OFFSET = 20;

  /**
   * Where the card's first digit sits, relative to the cut at 1KB: before it, across it and after
   * it, so that every way the cut can split the card is tried.
   */
  private static final List<Integer> OFFSETS_AROUND_THE_CUT =
      IntStream.rangeClosed(-FARTHEST_OFFSET, FARTHEST_OFFSET).boxed().toList();

  /** The {@code max-field-length} of these tests, the smallest allowed. */
  private static final DataSize MAX_FIELD_LENGTH = DataSize.ofKilobytes(1);

  /** The text Spring Boot prints before the exception's message. */
  private static final String EXCEPTION_PREFIX = "java.lang.IllegalStateException: ";

  /** Spring Boot's stack trace limit, as Stratum sets it for {@link #MAX_FIELD_LENGTH}. */
  private final int bootStackTraceLimit =
      Integer.parseInt(
          Objects.requireNonNull(
              runLoggingPostProcessor(
                      environmentWithModuleOnAndServiceName()
                          .withProperty(
                              LoggingConstants.PREFIX + ".max-field-length",
                              MAX_FIELD_LENGTH.toString()))
                  .getProperty(LoggingConstants.STACK_TRACE_MAX_LENGTH)));

  /** Masks with the default settings but a 1KB {@code max-field-length}. */
  private final Redactor redactor =
      Redactor.from(LoggingTestData.defaults().maxFieldLength(MAX_FIELD_LENGTH).build());

  /**
   * Returns a stack trace as Spring Boot prints it, root first, whose message puts the card's first
   * digit at the given distance from the cut at 1KB.
   */
  private String printedTrace(int offset) {
    var firstDigit = (int) MAX_FIELD_LENGTH.toBytes() + offset;
    var padding = "x".repeat(firstDigit - EXCEPTION_PREFIX.length() - 1);
    return StandardStackTracePrinter.rootFirst()
        .withMaximumLength(bootStackTraceLimit)
        .printStackTraceToString(
            new IllegalStateException(padding + " " + CARD_IN_GROUPS + " declined"));
  }

  /** Checks that no group of the card is left: a whole card is masked to {@code ****1111}. */
  private void assertNoPartialCard(String written) {
    assertThat(written).doesNotContain("4111");
  }

  @Nested
  @DisplayName("in a JSON format")
  class JsonPath {

    @ParameterizedTest(name = "first digit at the cut {0}")
    @FieldSource(
        "com.exepex.stratum.logging.redaction.StackTraceCutRedactionTest#OFFSETS_AROUND_THE_CUT")
    @DisplayName("no group of a card number at the cut is written")
    void cutTraceShowsNoPartialCard(int offset) {
      assertNoPartialCard(
          (String) redactor.redactStackTrace("error.stack_trace", printedTrace(offset)));
    }
  }

  @Nested
  @DisplayName("in the text format")
  class TextPath {

    @ParameterizedTest(name = "first digit at the cut {0}")
    @FieldSource(
        "com.exepex.stratum.logging.redaction.StackTraceCutRedactionTest#OFFSETS_AROUND_THE_CUT")
    @DisplayName("no group of a card number at the cut is written")
    void cutTraceShowsNoPartialCard(int offset) {
      assertNoPartialCard(redactor.redactAsText("error.stack_trace", printedTrace(offset)));
    }
  }
}
