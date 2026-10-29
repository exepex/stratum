package com.exepex.stratum.core.condition;

import lombok.experimental.UtilityClass;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;

/** The property keys that every Stratum module shares (ST-ENG-45). */
@UtilityClass
public class StratumModuleConstants {

  /** The first part of every Stratum property. */
  public static final String PREFIX = "stratum";

  /** The environment the service runs in, such as {@code prod}, which several modules report. */
  public static final String SERVICE_ENVIRONMENT = PREFIX + ".environment";

  /**
   * Whether the logging and observability modules make Spring Boot's executors carry their context,
   * through {@link #PROPAGATE_TASK_CONTEXT}. Defaults to {@code true}.
   */
  public static final String PROPAGATE_CONTEXT_TO_ASYNC_THREADS = PREFIX + ".propagate-context";

  /**
   * Spring Boot's switch that makes its executors carry the logging and tracing context, which the
   * logging and observability modules turn on by default.
   */
  public static final String PROPAGATE_TASK_CONTEXT = "spring.task.execution.propagate-context";

  /**
   * The {@code @AutoConfigureOrder} of every Stratum auto-configuration: later than the default, so
   * that a company framework's auto-configurations with no order run first and their beans win (ADR
   * 0001).
   */
  public static final int AUTO_CONFIGURATION_ORDER = AutoConfigureOrder.DEFAULT_ORDER + 1000;

  /** The last part of a module's on/off property. */
  public static final String ENABLED = "enabled";

  /**
   * Returns the on/off property of a module.
   *
   * @param module the module's kebab-case name, such as {@code http-client}
   * @return the property, such as {@code stratum.http-client.enabled}
   */
  public static String enabledProperty(String module) {
    return PREFIX + "." + module + "." + ENABLED;
  }
}
