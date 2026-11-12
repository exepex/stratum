package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import java.io.Serial;
import org.springframework.util.ReflectionUtils;

/**
 * Carries the failure an interceptor threw on a call of a client's interface, or the failed fetch
 * of the service's token before the first send, so that Stratum's retries and circuit breaker see
 * that nothing was sent: it is never retried, and the breaker ignores it. It never reaches the
 * service: the outermost layer of the call throws the original failure, type unchanged ({@link
 * #throwOriginal()}). It has no message and no stack trace of its own.
 */
@StratumInternal
final class InterceptorFailure extends RuntimeException {

  /** The version of this exception's serialized form. */
  @Serial private static final long serialVersionUID = 1L;

  /** The original failure, such as what the interceptor threw. */
  private final RuntimeException original;

  /**
   * Carries a failure raised before anything was sent.
   *
   * @param original the failure, such as what the interceptor threw
   */
  InterceptorFailure(RuntimeException original) {
    super(null, original, false, false);
    this.original = original;
  }

  /** Throws the original failure, as it was thrown. */
  void throwOriginal() {
    ReflectionUtils.rethrowRuntimeException(original);
  }
}
