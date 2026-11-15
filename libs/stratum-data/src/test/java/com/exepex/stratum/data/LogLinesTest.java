package com.exepex.stratum.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

@DisplayName("LogLines keeps a logged text on one line")
class LogLinesTest {

  @Nested
  @DisplayName("a text with line breaks")
  class WithBreaks {

    /** Texts that would start a forged line, with a carriage return, a line feed or both. */
    static final List<String> FORGED = List.of("batch\nINFO forged", "batch\rforged", "a\r\nb\nc");

    @ParameterizedTest(name = "{index}")
    @FieldSource("FORGED")
    @DisplayName("has every break replaced by a space")
    void replacesEveryBreak(String text) {
      assertThat(LogLines.oneLine(text)).doesNotContain("\n", "\r").hasSameSizeAs(text);
    }
  }

  @Nested
  @DisplayName("a text on one line")
  class OnOneLine {

    @Test
    @DisplayName("is kept as it is")
    void keepsText() {
      assertThat(LogLines.oneLine("stratum.data.sources.batch"))
          .isEqualTo("stratum.data.sources.batch");
    }
  }
}
