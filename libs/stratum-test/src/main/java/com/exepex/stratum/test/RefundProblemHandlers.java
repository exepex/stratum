package com.exepex.stratum.test;

import com.exepex.stratum.core.api.StratumInternal;
import java.io.Serial;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * The exception handlers of a sample refunds service that answers with Problem Details bodies of
 * its own, for the web tests that check what Stratum adds to such a body. A test application
 * declares a subclass annotated {@code @RestControllerAdvice}; this class has no such annotation,
 * so a service's component scan never picks it up.
 */
@StratumInternal
public class RefundProblemHandlers {

  /** The instance the receipt handler sets on its body. */
  public static final String SERVICE_INSTANCE = "urn:refund:7";

  /**
   * Answers a refused refund with a 402 body that has no instance.
   *
   * @return the body
   */
  @ExceptionHandler(RefundRefusedException.class)
  public ProblemDetail refundRefused() {
    return ProblemDetail.forStatusAndDetail(HttpStatus.PAYMENT_REQUIRED, "The refund was refused");
  }

  /**
   * Answers a missing receipt with a 404 body whose instance is {@link #SERVICE_INSTANCE}.
   *
   * @return the body
   */
  @ExceptionHandler(ReceiptMissingException.class)
  public ProblemDetail receiptMissing() {
    var body = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    body.setInstance(URI.create(SERVICE_INSTANCE));
    return body;
  }

  /** A refund the service refuses. */
  @StratumInternal
  public static class RefundRefusedException extends RuntimeException {

    /** Version of the serialized form. */
    @Serial private static final long serialVersionUID = 1L;
  }

  /** A receipt the service cannot find. */
  @StratumInternal
  public static class ReceiptMissingException extends RuntimeException {

    /** Version of the serialized form. */
    @Serial private static final long serialVersionUID = 1L;
  }
}
