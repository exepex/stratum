package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

@DisplayName("RefundProblemHandlers answers with the sample refunds service's own bodies")
class RefundProblemHandlersTest {

  /** The handlers under test. */
  private final RefundProblemHandlers handlers = new RefundProblemHandlers();

  @Nested
  @DisplayName("refundRefused")
  class RefundRefused {

    @Test
    @DisplayName("answers 402 with its detail and no instance")
    void paymentRequiredWithoutInstance() {
      var body = handlers.refundRefused();

      assertThat(body.getStatus()).isEqualTo(HttpStatus.PAYMENT_REQUIRED.value());
      assertThat(body.getDetail()).isEqualTo("The refund was refused");
      assertThat(body.getInstance()).isNull();
    }
  }

  @Nested
  @DisplayName("receiptMissing")
  class ReceiptMissing {

    @Test
    @DisplayName("answers 404 with the service's own instance")
    void notFoundWithServiceInstance() {
      var body = handlers.receiptMissing();

      assertThat(body.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
      assertThat(body.getInstance()).isEqualTo(URI.create(RefundProblemHandlers.SERVICE_INSTANCE));
    }
  }
}
