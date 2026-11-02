package com.exepex.stratum.logging.testsupport;

import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.mdc.MdcFieldsPropagation;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * Registers, reads and clears the logging module's two entries in Micrometer's context registry,
 * which the module keeps for the rest of the JVM once it ran, so each test starts from a known
 * registry.
 */
@UtilityClass
public class MdcFieldsRegistrations {

  /** The keys of the service MDC fields accessor and the workflow fields accessor. */
  private static final List<String> MDC_FIELDS_KEYS =
      List.of(LoggingConstants.SERVICE_MDC_FIELDS, LoggingConstants.WORKFLOW_FIELDS);

  /** Registers both accessors, as the logging module does while it is on. */
  public void register() {
    new MdcFieldsPropagation().afterSingletonsInstantiated();
  }

  /** Removes both accessors from the registry, as before the module first ran in this JVM. */
  public void remove() {
    MDC_FIELDS_KEYS.forEach(ContextRegistry.getInstance()::removeThreadLocalAccessor);
  }

  /**
   * Returns the keys of the module's accessors that Micrometer's shared registry holds now.
   *
   * @return the registered keys, in registry order
   */
  public List<Object> registeredKeys() {
    return registeredKeys(ContextRegistry.getInstance());
  }

  /**
   * Returns the keys of the module's accessors that the given registry holds now.
   *
   * @param registry the registry to read
   * @return the registered keys, in registry order
   */
  public List<Object> registeredKeys(ContextRegistry registry) {
    return registry.getThreadLocalAccessors().stream()
        .map(ThreadLocalAccessor::key)
        .filter(MDC_FIELDS_KEYS::contains)
        .toList();
  }
}
