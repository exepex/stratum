package com.exepex.stratum.web.testsupport;

import com.exepex.stratum.core.error.FailureWithErrorCode;
import com.exepex.stratum.test.OrderErrorCode;
import com.exepex.stratum.web.UnloggedFailure;
import java.io.Serial;
import lombok.Getter;

/** A coded failure answered without a log line while its code is mapped, as a refusal is. */
@Getter
public class RefusedOrderException extends RuntimeException
    implements FailureWithErrorCode, UnloggedFailure {

  /** Version of the serialized form. */
  @Serial private static final long serialVersionUID = 1L;

  /** The code of the failure. */
  private final OrderErrorCode errorCode;

  /**
   * Makes the failure.
   *
   * @param errorCode the code of the failure
   */
  public RefusedOrderException(OrderErrorCode errorCode) {
    super("refused order", null, false, false);
    this.errorCode = errorCode;
  }

  /**
   * Returns no detail: the code's description says it all.
   *
   * @return an empty detail
   */
  @Override
  public String getErrorDetail() {
    return "";
  }
}
