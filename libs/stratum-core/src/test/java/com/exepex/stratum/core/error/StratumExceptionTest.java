package com.exepex.stratum.core.error;

import static com.exepex.stratum.core.error.testsupport.ErrorTestData.QUANTITY_PROBLEM;
import static com.exepex.stratum.core.error.testsupport.ErrorTestData.SKU;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.exepex.orders.OrderErrorCode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StratumException carries an error report")
class StratumExceptionTest {

  @Test
  @DisplayName("its message starts with the code, and its report holds the code and message")
  void messageStartsWithCode() {
    var exception = new StratumException(OrderErrorCode.OUT_OF_STOCK, SKU);

    assertThat(exception).hasMessage("ORD-0001: Not enough stock: SKU-1").hasNoCause();
    assertThat(exception.getReport().getExternal().code()).isEqualTo("ORD-0001");
    assertThat(exception.getReport().getCause()).isNull();
  }

  @Test
  @DisplayName("without a detail, its message is the code and the code's description")
  void messageWithoutDetail() {
    assertThat(new StratumException(OrderErrorCode.OUT_OF_STOCK))
        .hasMessage("ORD-0001: Not enough stock");
  }

  @Test
  @DisplayName("with an empty detail, its message has no dangling colon")
  void messageWithEmptyDetail() {
    assertThat(new StratumException(OrderErrorCode.OUT_OF_STOCK, ""))
        .hasMessage("ORD-0001: Not enough stock");
  }

  @Test
  @DisplayName("the exception it wraps becomes its cause")
  void keepsCause() {
    var cause = new IllegalStateException("stock service down");

    var exception = new StratumException(OrderErrorCode.OUT_OF_STOCK, SKU, cause);

    assertThat(exception).hasCause(cause);
    assertThat(exception.getReport().getCause()).isSameAs(cause);
  }

  @Test
  @DisplayName("a report with field details keeps them, and the report's cause becomes its cause")
  void keepsDetailsAndCauseOfReport() {
    var cause = new IllegalStateException("stock service down");
    var report =
        ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, SKU)
            .withDetails(List.of(QUANTITY_PROBLEM))
            .withCause(cause);

    var exception = new StratumException(report);

    assertThat(exception).hasCause(cause);
    assertThat(exception.getReport().getExternal().details()).containsExactly(QUANTITY_PROBLEM);
  }

  @Test
  @DisplayName("a warning cannot be thrown")
  void rejectsWarning() {
    var warning = ErrorReport.of(OrderErrorCode.DELAYED, "3 days");

    assertThatIllegalArgumentException()
        .isThrownBy(() -> new StratumException(warning))
        .withMessage("ORD-0002 is a warning; log it instead of throwing it.");
  }
}
