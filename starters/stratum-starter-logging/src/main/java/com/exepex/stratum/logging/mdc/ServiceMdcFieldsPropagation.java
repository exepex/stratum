package com.exepex.stratum.logging.mdc;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.logging.LoggingConstants;
import io.micrometer.context.ContextRegistry;

/**
 * Registers {@link ServiceMdcFieldsAccessor} with Micrometer's context propagation while the
 * logging module is on, and removes it when the application stops, so that the service's own MDC
 * fields travel only when the module is enabled.
 */
@StratumInternal
public class ServiceMdcFieldsPropagation implements AutoCloseable {

  /** Starts carrying the service's own MDC fields to other threads. */
  public ServiceMdcFieldsPropagation() {
    ContextRegistry.getInstance().registerThreadLocalAccessor(new ServiceMdcFieldsAccessor());
  }

  /** Stops carrying them, when the application stops. */
  @Override
  public void close() {
    ContextRegistry.getInstance().removeThreadLocalAccessor(LoggingConstants.SERVICE_MDC_FIELDS);
  }
}
