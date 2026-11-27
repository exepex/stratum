package com.exepex.stratum.resilience;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.FailureWithErrorCode;
import com.exepex.stratum.web.UnloggedFailure;
import java.io.Serial;

/**
 * A request refused because its route already ran as many requests as its limit admits. The web
 * starter answers it with the status of {@code SX-RESILIENCE-0001} and writes no log line while
 * that code is mapped. It is not one of Spring's status exceptions, so Spring's own Problem Details
 * handling leaves it to the web starter, and it has no stack trace, which would cost each refusal.
 */
@StratumInternal
public class RequestRefusedException extends RuntimeException
    implements FailureWithErrorCode, UnloggedFailure {

  /** Version of the serialized form. */
  @Serial private static final long serialVersionUID = 1L;

  /** Makes the refusal, with the code's description as its message and no stack trace. */
  public RequestRefusedException() {
    super(ResilienceErrorCode.REQUEST_REFUSED.getDescription(), null, false, false);
  }

  /**
   * Returns {@code SX-RESILIENCE-0001}.
   *
   * @return the code
   */
  @Override
  public ErrorCode getErrorCode() {
    return ResilienceErrorCode.REQUEST_REFUSED;
  }

  /**
   * Returns no detail: the code's description says all a caller may learn.
   *
   * @return an empty detail
   */
  @Override
  public String getErrorDetail() {
    return "";
  }
}
