package com.exepex.stratum.web;

import com.exepex.stratum.core.api.StratumInternal;
import io.micrometer.context.ThreadLocalAccessor;
import org.jspecify.annotations.Nullable;

/**
 * Holds the deadline of the request whose work runs on the current thread, so that Spring's
 * executors and Reactor carry it to every thread the request's work moves to. They carry the
 * deadline itself, not a copy, so its time left stays live. Registered with Micrometer's context
 * propagation while the web module is on.
 */
@StratumInternal
public class RequestDeadlineAccessor implements ThreadLocalAccessor<RequestDeadline> {

  // Static: Micrometer's registry is shared by every application context in the JVM, so each
  // instance must read the same thread value.
  /** The deadline on each thread. */
  private static final ThreadLocal<RequestDeadline> CURRENT = new ThreadLocal<>();

  /**
   * Returns the key under which the deadline is carried.
   *
   * @return {@value WebConstants#REQUEST_DEADLINE_CONTEXT_KEY}
   */
  @Override
  public Object key() {
    return WebConstants.REQUEST_DEADLINE_CONTEXT_KEY;
  }

  /**
   * Returns the deadline on this thread.
   *
   * @return the deadline, or null outside a request's work
   */
  @Override
  public @Nullable RequestDeadline getValue() {
    return CURRENT.get();
  }

  /**
   * Puts a request's deadline on this thread.
   *
   * @param deadline the deadline of the request whose work runs here
   */
  @Override
  public void setValue(RequestDeadline deadline) {
    CURRENT.set(deadline);
  }

  /** Removes the deadline from this thread. */
  @Override
  public void setValue() {
    CURRENT.remove();
  }
}
