package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import java.io.Serial;
import org.springframework.util.ReflectionUtils;

/**
 * Carries the failure an interceptor threw on a call of a client's interface, so that Stratum's
 * retries and circuit breaker see that nothing was sent: it is never retried, and the breaker
 * ignores it. It never reaches the service: the outermost layer of the call throws the
 * interceptor's own failure, type unchanged ({@link #throwOriginal()}). It has no message and no
 * stack trace of its own.
 */
@StratumInternal
final class InterceptorFailure extends RuntimeException {

  /** The version of this exception's serialized form. */
  @Serial private static final long serialVersionUID = 1L;

  /** What the interceptor threw. */
  private final RuntimeException original;

  /**
   * Carries an interceptor's failure.
   *
   * @param original what the interceptor threw
   */
  InterceptorFailure(RuntimeException original) {
    super(null, original, false, false);
    this.original = original;
  }

  /** Throws what the interceptor threw, as it threw it. */
  void throwOriginal() {
    ReflectionUtils.rethrowRuntimeException(original);
  }
}
