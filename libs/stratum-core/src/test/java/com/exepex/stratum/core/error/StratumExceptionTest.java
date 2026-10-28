package com.exepex.stratum.core.error;

import static com.exepex.stratum.core.error.testsupport.ErrorTestData.SKU;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.example.orders.OrderErrorCode;
import com.example.orders.OrderWarningCode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StratumException carries an error report")
class StratumExceptionTest {

  @Test
  @DisplayName("its message starts with the code, and its report's cause is the exception itself")
  void messageStartsWithCode() {
    var exception = new StratumException(OrderErrorCode.OUT_OF_STOCK, SKU);

    assertThat(exception).hasMessage("ORD-0001: Not enough stock for SKU-1.").hasNoCause();
    assertThat(exception.getReport().getCause()).isSameAs(exception);
  }

  @Test
  @DisplayName("the exception it wraps becomes its cause")
  void keepsCause() {
    var cause = new IllegalStateException("stock service down");

    var exception = new StratumException(cause, OrderErrorCode.OUT_OF_STOCK, SKU);

    assertThat(exception).hasCause(cause);
    assertThat(exception.getReport().getCause()).isSameAs(exception);
  }

  @Test
  @DisplayName("a report with field details keeps them, and the report's cause becomes its cause")
  void keepsDetailsAndCauseOfReport() {
    var cause = new IllegalStateException("stock service down");
    var detail = new ErrorDetail("quantity", "must be positive");
    var report =
        ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, SKU)
            .withDetails(List.of(detail))
            .withCause(cause);

    var exception = new StratumException(report);

    assertThat(exception).hasCause(cause);
    assertThat(exception.getReport().getDetails()).containsExactly(detail);
  }

  @Test
  @DisplayName("a warning cannot be thrown")
  void rejectsWarning() {
    var warning = ErrorReport.of(OrderWarningCode.DELAYED, "3 days");

    assertThatIllegalArgumentException()
        .isThrownBy(() -> new StratumException(warning))
        .withMessage("ORD-0101 is a warning; log it instead of throwing it.");
  }
}
