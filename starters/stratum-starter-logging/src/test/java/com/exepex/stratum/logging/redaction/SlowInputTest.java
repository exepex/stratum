package com.exepex.stratum.logging.redaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.exepex.stratum.logging.testsupport.LoggingTestData;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Redaction stays fast on hostile text of the largest allowed size")
class SlowInputTest {

  /** Characters in each input: just under the default 16KB limit. */
  private static final int LENGTH = 16_000;

  /** Far above the few milliseconds each input takes, and far below the seconds it once took. */
  private static final Duration LIMIT = Duration.ofSeconds(1);

  private final Redactor redactor = Redactor.from(LoggingTestData.defaults().build());

  /** Texts that once took seconds, or could, because a pattern scanned the rest at each start. */
  private static Stream<String> hostileInputs() {
    return Stream.of(
        "a".repeat(LENGTH) + "@",
        filled("eyJ"),
        "password=" + filled("word "),
        filled("+1 "),
        filled("1 "));
  }

  /** Repeats the text until it is about {@link #LENGTH} characters long. */
  private static String filled(String unit) {
    return unit.repeat(LENGTH / unit.length());
  }

  @ParameterizedTest
  @MethodSource("hostileInputs")
  @DisplayName("each input is masked within a second")
  void hostileInputIsFast(String input) {
    var masked = assertTimeoutPreemptively(LIMIT, () -> redactor.redact("message", input));

    assertThat(masked).isNotNull();
  }
}
