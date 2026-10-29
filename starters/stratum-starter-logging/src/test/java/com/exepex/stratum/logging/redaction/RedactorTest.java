package com.exepex.stratum.logging.redaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.logging.testsupport.LoggingTestData;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.beans.BeanUtils;
import org.springframework.util.unit.DataSize;

@DisplayName("Redactor checks its settings, limits each value and fails closed")
class RedactorTest {

  /** The smallest allowed limit, 1KB, in characters. */
  private static final int SMALLEST_MAX_FIELD_LENGTH = 1024;

  /**
   * How many {@code ab} pairs make Java's regex engine overflow a {@link #SMALL_STACK_BYTES} stack
   * on {@code (a|b)*c}, while staying under the default 16KB limit.
   */
  private static final int OVERFLOWING_PAIRS = 8000;

  /** The stack size of the thread that overflows, so the test does not rely on the JVM default. */
  private static final long SMALL_STACK_BYTES = 256 * 1024;

  /** How long the overflowing thread may take to give its result. */
  private static final long OVERFLOW_WAIT_SECONDS = 10;

  /** The field that holds a log line's message. */
  private static final String MESSAGE = "message";

  @Nested
  @DisplayName("when it is made")
  class WhenBuiltFromProperties {

    /** Extra patterns that are not valid regular expressions, each naming a secret. */
    private static final List<String> INVALID_PATTERNS =
        List.of("secret-(\\d+", "\\p{secret-name}", "(?<secret>a)(?<secret>b)");

    @ParameterizedTest
    @FieldSource("INVALID_PATTERNS")
    @DisplayName("an invalid extra pattern stops startup with SX-LOGGING-1002, with no part of it")
    void invalidPatternIsRejected(String invalid) {
      var properties =
          LoggingTestData.withRedaction(List.of(), List.of("ok-\\d+", invalid)).build();

      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(() -> Redactor.from(properties))
          .withMessageStartingWith("SX-LOGGING-1002: ")
          .withMessageContaining("pattern 2")
          .withMessageNotContaining("secret");
    }
  }

  @Nested
  @DisplayName("when it masks a value")
  class WhenMaskingValues {

    /** The exceptions a failed masking can throw, each replaced by the failure marker. */
    private static final List<Class<? extends RuntimeException>> MASKING_FAILURE_TYPES =
        List.of(
            IllegalArgumentException.class,
            IllegalStateException.class,
            IndexOutOfBoundsException.class);

    /** The separators that a cut must drop, with the digits before them. */
    private static final List<String> GROUP_SEPARATORS_A_CUT_DROPS = List.of(".", "_", "/");

    private final Redactor redactor =
        Redactor.from(LoggingTestData.defaults().maxFieldLength(DataSize.ofKilobytes(1)).build());

    @Test
    @DisplayName("a value even one character longer than the limit is cut and marked")
    void longValueIsCut() {
      var words = "word ".repeat(SMALLEST_MAX_FIELD_LENGTH / "word ".length() + 1);
      var cut =
          (String) redactor.redact(MESSAGE, words.substring(0, SMALLEST_MAX_FIELD_LENGTH + 1));

      assertThat(cut)
          .hasSize(SMALLEST_MAX_FIELD_LENGTH + Redactor.TRUNCATION_MARKER.length())
          .endsWith(Redactor.TRUNCATION_MARKER);
    }

    @Test
    @DisplayName("a card or token that crosses the limit is dropped, not left half visible")
    void secretAcrossTheLimitIsDropped() {
      var beforeCard = "y".repeat(SMALLEST_MAX_FIELD_LENGTH - "4111 1111 1".length()) + " ";
      var beforeToken = "z".repeat(SMALLEST_MAX_FIELD_LENGTH - "opaque".length()) + " ";

      assertThat(redactor.redact(MESSAGE, beforeCard + "4111 1111 1111 1111"))
          .isEqualTo(beforeCard.strip() + Redactor.TRUNCATION_MARKER);
      assertThat(redactor.redact(MESSAGE, beforeToken + "opaque-token-value"))
          .isEqualTo(beforeToken.strip() + Redactor.TRUNCATION_MARKER);
    }

    @ParameterizedTest
    @FieldSource("GROUP_SEPARATORS_A_CUT_DROPS")
    @DisplayName("card digits split by a dot, underscore or slash that cross the limit are dropped")
    void groupsAcrossTheLimitAreDropped(String separator) {
      var groups = "4111" + separator + "1111 1111" + separator + "1111";
      var beforeGroups =
          "y".repeat(SMALLEST_MAX_FIELD_LENGTH - groups.length() + "111".length()) + " ";

      assertThat(redactor.redact(MESSAGE, beforeGroups + groups))
          .isEqualTo(beforeGroups.strip() + Redactor.TRUNCATION_MARKER);
    }

    @Test
    @DisplayName("a value with no space that is over the limit is dropped whole")
    void longWordIsDroppedWhole() {
      assertThat(redactor.redact(MESSAGE, "x".repeat(SMALLEST_MAX_FIELD_LENGTH + 1)))
          .isEqualTo(Redactor.TRUNCATION_MARKER);
    }

    @Test
    @DisplayName("a value of exactly the limit is kept whole")
    void valueAtTheLimitIsKept() {
      var value = "x".repeat(SMALLEST_MAX_FIELD_LENGTH);

      assertThat(redactor.redact(MESSAGE, value)).isEqualTo(value);
    }

    @ParameterizedTest
    @FieldSource("MASKING_FAILURE_TYPES")
    @DisplayName("a value that fails to mask is replaced, never written as it was")
    void failureIsReplaced(Class<? extends RuntimeException> failureType) {
      var broken =
          new Object() {
            @Override
            public String toString() {
              throw BeanUtils.instantiateClass(failureType);
            }
          };

      assertThat(redactor.redact("cardNumber", broken)).isEqualTo(Redactor.REDACTION_FAILED);
    }

    @Test
    @DisplayName("a list whose toString() returns null, under a card name, is masked")
    void nullTextListUnderCardNameIsMasked() {
      var withoutText =
          new ArrayList<String>() {
            @Override
            public String toString() {
              return null;
            }
          };

      assertThat(redactor.redact("card_number", withoutText)).isEqualTo(MaskedText.MASK);
    }

    @Test
    @DisplayName("a value whose toString() returns null is written as the text null")
    void nullTextIsWrittenAsNull() {
      var withoutText =
          new Object() {
            @Override
            public String toString() {
              return null;
            }
          };

      assertThat(redactor.redact(MESSAGE, withoutText)).isEqualTo("null");
    }

    @Test
    @DisplayName("an extra pattern that overflows the stack fails closed, without throwing")
    void stackOverflowIsReplaced() throws Exception {
      var withDeepPattern =
          Redactor.from(LoggingTestData.withRedaction(List.of(), List.of("(a|b)*c")).build());
      var redaction =
          new FutureTask<>(() -> withDeepPattern.redact(MESSAGE, "ab".repeat(OVERFLOWING_PAIRS)));
      new Thread(null, redaction, "small-stack", SMALL_STACK_BYTES).start();

      assertThat(redaction.get(OVERFLOW_WAIT_SECONDS, TimeUnit.SECONDS))
          .isEqualTo(Redactor.REDACTION_FAILED);
    }

    @Test
    @DisplayName("a null value stays null")
    void nullStaysNull() {
      assertThat(redactor.redact("password", null)).isNull();
    }
  }
}
