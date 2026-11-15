package com.exepex.stratum.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.slf4j.Logger;

@DisplayName("LogLines keeps a logged text on one line")
class LogLinesTest {

  @Nested
  @DisplayName("a text with line breaks")
  class WithBreaks {

    /** Texts that would start a forged line, with a carriage return, a line feed or both. */
    private static final List<String> FORGED =
        List.of("batch\nINFO forged", "batch\rforged", "a\r\nb\nc");

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

  @Nested
  @DisplayName("a line logged through it")
  class Logged {

    /** A logger whose levels each test sets. */
    private final Logger log = mock(Logger.class);

    /** Fails if the line is built, for a level that is off. */
    private String unbuilt() {
      throw new AssertionError("the line was built for a level that is off");
    }

    @Test
    @DisplayName("at info, with info on, is logged on one line")
    void infoOn() {
      when(log.isInfoEnabled()).thenReturn(true);

      LogLines.info(log, () -> "batch\nforged");

      verify(log).info("batch forged");
    }

    @Test
    @DisplayName("at debug, with debug on, is logged on one line")
    void debugOn() {
      when(log.isDebugEnabled()).thenReturn(true);

      LogLines.debug(log, () -> "batch\rforged");

      verify(log).debug("batch forged");
    }

    @Test
    @DisplayName("at a level that is off, is neither built nor logged")
    void levelOff() {
      LogLines.info(log, this::unbuilt);
      LogLines.debug(log, this::unbuilt);

      verify(log).isInfoEnabled();
      verify(log).isDebugEnabled();
      verifyNoMoreInteractions(log);
    }
  }
}
