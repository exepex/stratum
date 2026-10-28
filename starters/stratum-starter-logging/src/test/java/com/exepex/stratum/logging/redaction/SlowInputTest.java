package com.exepex.stratum.logging.redaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.exepex.stratum.logging.testsupport.LoggingTestData;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.util.unit.DataSize;

@DisplayName("Redaction stays fast on hostile text, up to the largest allowed size")
class SlowInputTest {

  /** Characters in each input at the default limit: just under 16KB. */
  private static final int DEFAULT_LENGTH = 16_000;

  /** Characters in each input at the largest allowed limit: just under 1MB. */
  private static final int LARGEST_LENGTH = 1_000_000;

  /**
   * Far above the few milliseconds each 16KB input takes, and far below the seconds it once took.
   */
  private static final Duration DEFAULT_LIMIT = Duration.ofSeconds(1);

  /** Linear work on 1MB takes well under this; work that grows with the square takes minutes. */
  private static final Duration LARGEST_LIMIT = Duration.ofSeconds(5);

  private final Redactor redactor = Redactor.from(LoggingTestData.defaults().build());

  private final Redactor largestRedactor =
      Redactor.from(LoggingTestData.defaults().maxFieldLength(DataSize.ofMegabytes(1)).build());

  /** Texts of about 16KB that once took seconds, or could, because a pattern rescanned the rest. */
  private static Stream<String> hostileInputs() {
    return Stream.concat(
        Stream.of(
            "a".repeat(DEFAULT_LENGTH) + "@",
            filled("a", DEFAULT_LENGTH).substring(DEFAULT_LENGTH / 2)
                + "@"
                + filled("a.", DEFAULT_LENGTH).substring(DEFAULT_LENGTH / 2),
            filled("a.", DEFAULT_LENGTH).substring(DEFAULT_LENGTH / 2)
                + "@"
                + filled("b-", DEFAULT_LENGTH).substring(DEFAULT_LENGTH / 2),
            "password=" + filled("word ", DEFAULT_LENGTH),
            filled("+1 ", DEFAULT_LENGTH)),
        largeHostileInputs(DEFAULT_LENGTH));
  }

  /** The same kinds of text at about 1MB. */
  private static Stream<String> largestHostileInputs() {
    return largeHostileInputs(LARGEST_LENGTH);
  }

  /** Hostile texts that are also checked at the largest allowed size. */
  private static Stream<String> largeHostileInputs(int length) {
    return Stream.of(
        filled("a=", length),
        filled("a:", length),
        filled("eyJ", length),
        filled("a.", length) + "://",
        filled("a://:", length),
        filled("0 ", length),
        filled("1 ", length),
        filled("1 ", length) + "1a",
        filled("4111111111111111 ", length),
        filled("GB82 WEST 1234 5698 7654 32 ", length));
  }

  /** Repeats the text until it is about the given number of characters long. */
  private static String filled(String unit, int length) {
    return unit.repeat(length / unit.length());
  }

  @ParameterizedTest
  @MethodSource("hostileInputs")
  @DisplayName("each 16KB input is masked within a second")
  void hostileInputIsFast(String input) {
    var masked = assertTimeoutPreemptively(DEFAULT_LIMIT, () -> redactor.redact("message", input));

    assertThat(masked).isNotNull();
  }

  @ParameterizedTest
  @MethodSource("largestHostileInputs")
  @DisplayName("each 1MB input is masked in linear time")
  void largestHostileInputIsLinear(String input) {
    var masked =
        assertTimeoutPreemptively(LARGEST_LIMIT, () -> largestRedactor.redact("message", input));

    assertThat(masked).isNotNull();
  }
}
