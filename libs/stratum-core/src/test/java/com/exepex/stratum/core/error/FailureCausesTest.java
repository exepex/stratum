package com.exepex.stratum.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.orders.OrderErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FailureCauses finds the error code of a failure or of one of its causes")
class FailureCausesTest {

  @Test
  @DisplayName("a failure with a code is found itself, before a cause with another code")
  void findsOwnCode() {
    var failure =
        new StratumException(
            OrderErrorCode.OUT_OF_STOCK, "", new StratumException(CoreErrorCode.INVALID_SETTINGS));

    assertThat(FailureCauses.findFailureWithErrorCode(failure)).containsSame(failure);
  }

  @Test
  @DisplayName("a plain wrapper gives its first cause with a code")
  void findsFirstCauseWithCode() {
    var coded = new StratumException(OrderErrorCode.OUT_OF_STOCK);
    var failure = new IllegalStateException(new RuntimeException(coded));

    assertThat(FailureCauses.findFailureWithErrorCode(failure)).containsSame(coded);
  }

  @Test
  @DisplayName("a chain of causes that loops back on itself is followed once")
  void followsLoopOnce() {
    var outer = new IllegalStateException("outer");
    var inner = new IllegalArgumentException("inner", outer);
    outer.initCause(inner);

    assertThat(FailureCauses.causesOf(outer)).containsExactly(outer, inner);
    assertThat(FailureCauses.findFailureWithErrorCode(outer)).isEmpty();
  }

  @Test
  @DisplayName("no failure has no causes and no code")
  void noFailure() {
    assertThat(FailureCauses.causesOf(null)).isEmpty();
    assertThat(FailureCauses.findFailureWithErrorCode(null)).isEmpty();
  }
}
