package com.exepex.stratum.observability.linkedwork;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("WorkOrigin is stored as text and read back unchanged, and refuses any other form")
class WorkOriginTest {

  /** A trace ID in W3C form. */
  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  /** A span ID in W3C form. */
  private static final String SPAN_ID = "00f067aa0ba902b7";

  /** A correlation ID. */
  private static final String CORRELATION_ID = "order-42";

  @Test
  @DisplayName("an origin stored with toText is read back equal by fromText, sampled or not")
  void readsBackWhatItStores() {
    var sampled = new WorkOrigin(TRACE_ID, SPAN_ID, true, CORRELATION_ID);
    var notSampled = new WorkOrigin(TRACE_ID, SPAN_ID, false, CORRELATION_ID);

    assertThat(sampled.toText())
        .isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-01 " + CORRELATION_ID);
    assertThat(WorkOrigin.fromText(sampled.toText())).isEqualTo(sampled);
    assertThat(WorkOrigin.fromText(notSampled.toText())).isEqualTo(notSampled);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01 order-42",
        "01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01 order-42",
        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01 order 42",
        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01 order-42\nforged"
      })
  @DisplayName("stored text in another form is refused, and the message never shows it")
  void refusesOtherText(String storedOrigin) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> WorkOrigin.fromText(storedOrigin))
        .withMessageNotContaining("order");
  }

  @Test
  @DisplayName("parts in another form are refused, and the message never shows them")
  void refusesOtherParts() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new WorkOrigin("trace", SPAN_ID, true, CORRELATION_ID));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new WorkOrigin(TRACE_ID, "span", true, CORRELATION_ID));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new WorkOrigin(TRACE_ID, SPAN_ID, true, "order\n42"))
        .withMessageNotContaining("order\n42");
  }
}
