package com.exepex.stratum.web;

import com.exepex.stratum.core.api.StratumInternal;
import io.micrometer.context.ContextRegistry;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Registers {@link RequestDeadlineAccessor} with Micrometer's context propagation once the web
 * module's beans exist, so that a request's deadline travels with its work to other threads only
 * while the module is on.
 */
@StratumInternal
public class RequestDeadlinePropagation implements SmartInitializingSingleton {

  /**
   * Starts carrying request deadlines to other threads. The accessor stays registered for the rest
   * of the JVM, because the registry is shared by every application context in it.
   */
  @Override
  public void afterSingletonsInstantiated() {
    // Spring Boot never makes a SmartInitializingSingleton lazy, so this also runs under
    // spring.main.lazy-initialization=true.
    ContextRegistry.getInstance().registerThreadLocalAccessor(new RequestDeadlineAccessor());
  }
}
