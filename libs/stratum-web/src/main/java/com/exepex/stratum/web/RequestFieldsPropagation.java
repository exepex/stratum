package com.exepex.stratum.web;

import io.micrometer.context.ContextRegistry;

/**
 * Registers {@link RequestFieldsAccessor} with Micrometer's context propagation while the web
 * module is on, and removes it when the application stops, so that request fields travel with a
 * request's work to other threads.
 */
public class RequestFieldsPropagation implements AutoCloseable {

  /** Starts carrying the request fields to other threads. */
  public RequestFieldsPropagation() {
    ContextRegistry.getInstance().registerThreadLocalAccessor(new RequestFieldsAccessor());
  }

  /** Stops carrying them, when the application stops. */
  @Override
  public void close() {
    ContextRegistry.getInstance()
        .removeThreadLocalAccessor(WebConstants.REQUEST_FIELDS_CONTEXT_KEY);
  }
}
