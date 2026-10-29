package com.exepex.stratum.logging.redaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.logging.testsupport.LoggingTestData;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;

@DisplayName("Redactor checks its settings, limits each value and fails closed")
class RedactorTest {

  /** The smallest allowed limit, 1KB, in characters. */
  private static final int SMALLEST_LIMIT = 1024;

  /** The field that holds a log line's message. */
  private static final String MESSAGE = "message";

  @Nested
  @DisplayName("when it is made")
  class Settings {

    @ParameterizedTest
    @ValueSource(strings = {"secret-(\\d+", "\\p{secret-name}", "(?<secret>a)(?<secret>b)"})
    @DisplayName("an invalid extra pattern stops startup with SX-LOGGING-1002, with no part of it")
    void invalidPatternIsRejected(String invalid) {
      var settings = LoggingTestData.withRedaction(List.of(), List.of("ok-\\d+", invalid));

      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(() -> Redactor.from(settings))
          .withMessageStartingWith("SX-LOGGING-1002: ")
          .withMessageContaining("pattern 2")
          .withMessageNotContaining("secret");
    }
  }

  @Nested
  @DisplayName("when it masks a value")
  class Values {

    private final Redactor redactor =
        Redactor.from(LoggingTestData.defaults().maxFieldLength(DataSize.ofKilobytes(1)).build());

    @Test
    @DisplayName("a value even one character longer than the limit is cut and marked")
    void longValueIsCut() {
      var words = "word ".repeat(SMALLEST_LIMIT / "word ".length() + 1);
      var cut = (String) redactor.redact(MESSAGE, words.substring(0, SMALLEST_LIMIT + 1));

      assertThat(cut)
          .hasSize(SMALLEST_LIMIT + Redactor.TRUNCATED.length())
          .endsWith(Redactor.TRUNCATED);
    }

    @Test
    @DisplayName("a card or token that crosses the limit is dropped, not left half visible")
    void secretAcrossTheLimitIsDropped() {
      var beforeCard = "y".repeat(SMALLEST_LIMIT - "4111 1111 1".length()) + " ";
      var beforeToken = "z".repeat(SMALLEST_LIMIT - "opaque".length()) + " ";

      assertThat(redactor.redact(MESSAGE, beforeCard + "4111 1111 1111 1111"))
          .isEqualTo(beforeCard.strip() + Redactor.TRUNCATED);
      assertThat(redactor.redact(MESSAGE, beforeToken + "opaque-token-value"))
          .isEqualTo(beforeToken.strip() + Redactor.TRUNCATED);
    }

    @Test
    @DisplayName("a value of exactly the limit is kept whole")
    void valueAtTheLimitIsKept() {
      var value = "x".repeat(SMALLEST_LIMIT);

      assertThat(redactor.redact(MESSAGE, value)).isEqualTo(value);
    }

    @Test
    @DisplayName("a value that fails to mask is replaced, never written as it was")
    void failureIsReplaced() {
      var broken =
          new Object() {
            @Override
            public String toString() {
              throw new IllegalStateException("broken toString");
            }
          };

      assertThat(redactor.redact("cardNumber", broken)).isEqualTo(Redactor.FAILED);
    }

    @Test
    @DisplayName("a null value stays null")
    void nullStaysNull() {
      assertThat(redactor.redact("password", null)).isNull();
    }
  }
}
