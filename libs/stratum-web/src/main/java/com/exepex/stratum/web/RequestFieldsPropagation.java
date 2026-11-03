package com.exepex.stratum.web;

import io.micrometer.context.ContextRegistry;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Registers {@link RequestFieldsAccessor} with Micrometer's context propagation once the web
 * module's beans exist, so that request fields travel with a request's work to other threads only
 * while the module is on.
 */
public class RequestFieldsPropagation implements SmartInitializingSingleton {

  /**
   * Starts carrying the request fields to other threads. They stay registered for the rest of the
   * JVM, because the registry is shared by every application context in it.
   */
  @Override
  public void afterSingletonsInstantiated() {
    // Spring Boot never makes a SmartInitializingSingleton lazy, so this also runs under
    // spring.main.lazy-initialization=true (issue #118).
    ContextRegistry.getInstance().registerThreadLocalAccessor(new RequestFieldsAccessor());
  }
}
