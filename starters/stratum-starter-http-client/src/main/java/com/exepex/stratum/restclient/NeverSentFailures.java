package com.exepex.stratum.restclient;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * Tells which failures stopped a call before anything was sent, for the circuit breaker, which
 * ignores them, and for the outcome the call's log line and meters show.
 */
@UtilityClass
class NeverSentFailures {

  /** The codes of a call that stopped before anything was sent. */
  private static final Set<HttpClientErrorCode> NEVER_SENT_CODES =
      Set.of(
          HttpClientErrorCode.INTERCEPTOR_BODY_TOO_LARGE, HttpClientErrorCode.CALLER_TOKEN_MISSING);

  /**
   * Returns whether a call failed before anything was sent: an interceptor failed, or read a body
   * larger than the client's {@code interceptor-body-limit} ({@code SX-HTTPC-0005}); the service's
   * token could not be fetched for the first send; or the call has no caller token to relay ({@code
   * SX-HTTPC-0004}). The breaker ignores such a call: it is neither a success nor a failure of the
   * other service.
   *
   * @param failure the failure of a call
   * @return whether nothing was sent
   */
  static boolean isNeverSent(Throwable failure) {
    return failure instanceof InterceptorFailure
        || failure instanceof StratumException stratum
            && NEVER_SENT_CODES.contains(stratum.getErrorCode());
  }

  /**
   * Returns the outcome of a call that a failure stopped before an attempt was sent: unavailable
   * when the service token could not be fetched, which arrives marked as never sent, and an error
   * for any other.
   *
   * @param failure the failure, such as {@code SX-HTTPC-0002}, or the carrier that marks it
   * @return the call's outcome
   */
  static CallOutcome notSentOutcome(RuntimeException failure) {
    var stopped = failure instanceof InterceptorFailure carrier ? carrier.getCause() : failure;
    return stopped instanceof StratumException stratum
            && stratum.getErrorCode() == HttpClientErrorCode.DOWNSTREAM_UNAVAILABLE
        ? CallOutcome.UNAVAILABLE
        : CallOutcome.ERROR;
  }
}
