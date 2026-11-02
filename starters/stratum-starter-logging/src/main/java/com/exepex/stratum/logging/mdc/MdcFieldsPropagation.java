package com.exepex.stratum.logging.mdc;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.logging.workflow.WorkflowFieldsAccessor;
import io.micrometer.context.ContextRegistry;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Registers the service's own MDC fields and the workflow fields with Micrometer's context
 * propagation once the logging module's beans exist, so that Spring's executors and Reactor carry
 * them to other threads only while the module is on.
 */
@StratumInternal
public class MdcFieldsPropagation implements SmartInitializingSingleton {

  /**
   * Starts carrying both sets of fields to other threads. They stay registered for the rest of the
   * JVM, because the registry is shared by every application context in it.
   */
  @Override
  public void afterSingletonsInstantiated() {
    // Spring Boot never makes a SmartInitializingSingleton lazy, so this also runs under
    // spring.main.lazy-initialization=true (issue #111).
    var registry = ContextRegistry.getInstance();
    registry.registerThreadLocalAccessor(new ServiceMdcFieldsAccessor());
    registry.registerThreadLocalAccessor(new WorkflowFieldsAccessor());
  }
}
