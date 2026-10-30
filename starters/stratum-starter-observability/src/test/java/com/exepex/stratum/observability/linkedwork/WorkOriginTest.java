package com.exepex.stratum.observability.linkedwork;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

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
    var sampled = sampledOrigin().build();
    var notSampled = sampledOrigin().sampled(false).build();

    assertThat(sampled.toText())
        .isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-01 " + CORRELATION_ID);
    assertThat(WorkOrigin.fromText(sampled.toText())).isEqualTo(sampled);
    assertThat(WorkOrigin.fromText(notSampled.toText())).isEqualTo(notSampled);
  }

  /**
   * Stored origins in another form: uppercase, another version, no correlation ID, an all-zero
   * trace or span ID, a space in the correlation ID and a forged line.
   */
  private static final List<String> STORED_ORIGINS_IN_ANOTHER_FORM =
      List.of(
          "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01 order-42",
          "01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01 order-42",
          "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
          "00-00000000000000000000000000000000-00f067aa0ba902b7-01 order-42",
          "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01 order-42",
          "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01 order 42",
          "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01 order-42\nforged");

  @ParameterizedTest
  @FieldSource("STORED_ORIGINS_IN_ANOTHER_FORM")
  @DisplayName("stored text in another form is refused, and the message never shows it")
  void refusesOtherText(String storedOrigin) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> WorkOrigin.fromText(storedOrigin))
        .withMessageNotContaining("order");
  }

  @Test
  @DisplayName("parts in another form are refused, and the message never shows them")
  void refusesOtherParts() {
    assertThatIllegalArgumentException().isThrownBy(sampledOrigin().traceId("trace")::build);
    assertThatIllegalArgumentException().isThrownBy(sampledOrigin().spanId("span")::build);
    assertThatIllegalArgumentException()
        .isThrownBy(sampledOrigin().correlationId("order\n42")::build)
        .withMessageNotContaining("order\n42");
  }

  /** Returns a builder for a valid, sampled origin. */
  private WorkOrigin.WorkOriginBuilder sampledOrigin() {
    return WorkOrigin.builder()
        .traceId(TRACE_ID)
        .spanId(SPAN_ID)
        .sampled(true)
        .correlationId(CORRELATION_ID);
  }
}
