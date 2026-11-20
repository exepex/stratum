package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.Severity;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import java.io.Serial;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The service's own error code that a client's {@code errors} setting gives an error status, such
 * as {@code ORD-0409}. It is an error, and its description is the fixed text of {@code
 * SX-HTTPC-0007}, so the caller learns nothing more of the other service's answer.
 */
@StratumInternal
@Getter
@RequiredArgsConstructor
public final class MappedErrorCode implements ErrorCode {

  @Serial private static final long serialVersionUID = 1L;

  /** The code, as set, such as {@code ORD-0409}. */
  private final String code;

  /**
   * Returns that the code is an error, as the call failed.
   *
   * @return {@link Severity#ERROR}
   */
  @Override
  public Severity getSeverity() {
    return Severity.ERROR;
  }

  /**
   * Returns the fixed description of an error answer, the same as {@code SX-HTTPC-0007}'s.
   *
   * @return the description
   */
  @Override
  public String getDescription() {
    return HttpClientErrorCode.DOWNSTREAM_ERROR.getDescription();
  }
}
