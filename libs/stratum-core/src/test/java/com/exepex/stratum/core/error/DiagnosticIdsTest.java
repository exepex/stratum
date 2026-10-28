package com.exepex.stratum.core.error;

import static com.exepex.stratum.core.error.testsupport.ErrorTestData.SPAN_ID;
import static com.exepex.stratum.core.error.testsupport.ErrorTestData.TRACE_ID;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

@DisplayName("DiagnosticIds keeps only IDs that are safe to log and show")
class DiagnosticIdsTest {

  /** Leaves the logging context empty for the next test. */
  @AfterEach
  void clearLoggingContext() {
    MDC.clear();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "c0a8012e-7f3d-4a8e-9a43-2f9b6f1c2d10",
        "4bf92f3577b34da6a3ce929d0e0e4736",
        "order_42.step:3",
        "a"
      })
  @DisplayName("an ID of letters, digits, '-', '_', '.' and ':' is usable")
  void acceptsUsableId(String id) {
    assertThat(DiagnosticIds.isUsable(id)).isTrue();
  }

  @ParameterizedTest(name = "\"{0}\"")
  @NullAndEmptySource
  @ValueSource(strings = {"order 42", "id\nforged log line", "id\r", "<script>", "ü"})
  @DisplayName("a missing or empty ID, or one with any other character, is not usable")
  void rejectsUnusableId(String id) {
    assertThat(DiagnosticIds.isUsable(id)).isFalse();
  }

  @Test
  @DisplayName("an ID of the longest allowed length is usable, and one character more is not")
  void limitsLength() {
    var longest = "a".repeat(ErrorConstants.MAX_ID_LENGTH);

    assertThat(DiagnosticIds.isUsable(longest)).isTrue();
    assertThat(DiagnosticIds.isUsable(longest + "a")).isFalse();
  }

  @Test
  @DisplayName("an unusable ID in the logging context is left out, and the usable ones kept")
  void leavesOutUnusableIdFromContext() {
    MDC.put(ErrorConstants.TRACE_ID_KEY, TRACE_ID);
    MDC.put(ErrorConstants.SPAN_ID_KEY, SPAN_ID);
    MDC.put(ErrorConstants.CORRELATION_ID_KEY, "order-42\nFAKE LOG LINE");

    assertThat(DiagnosticIds.current()).isEqualTo(new DiagnosticIds(TRACE_ID, SPAN_ID, null));
  }
}
