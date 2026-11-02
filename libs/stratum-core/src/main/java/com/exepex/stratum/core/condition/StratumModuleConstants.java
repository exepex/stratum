package com.exepex.stratum.core.condition;

import com.exepex.stratum.core.api.StratumInternal;
import lombok.experimental.UtilityClass;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;

/** The property keys that every Stratum module shares (ST-ENG-45). */
@StratumInternal
@UtilityClass
public class StratumModuleConstants {

  /** The first part of every Stratum property. */
  public static final String PREFIX = "stratum";

  /** The environment the service runs in, such as {@code prod}, which several modules report. */
  public static final String SERVICE_ENVIRONMENT = PREFIX + ".environment";

  /**
   * Spring Boot's switch that makes its executors carry the logging and tracing context, which the
   * logging and observability modules turn on by default. A service turns it off by setting it to
   * {@code false}.
   */
  public static final String PROPAGATE_TASK_CONTEXT = "spring.task.execution.propagate-context";

  /**
   * Whether the logging and observability modules wrap the pool beans the service declares, such as
   * an {@code ExecutorService}, so that their tasks keep the logging and tracing context. Defaults
   * to {@code true}.
   */
  public static final String CONTEXT_PROPAGATION_EXECUTOR_BEANS =
      PREFIX + ".context-propagation.executor-beans";

  /** The name of the executor Spring Boot makes for {@code @Async} methods and other tasks. */
  public static final String APPLICATION_TASK_EXECUTOR = "applicationTaskExecutor";

  /** The bean name {@code @Async} looks for when the service has several task executors. */
  public static final String ASYNC_TASK_EXECUTOR = "taskExecutor";

  /**
   * Spring Boot's setting for how Reactor carries the logging and tracing context, which the
   * logging and observability modules, and the web module on WebFlux, set to {@link
   * #REACTOR_CONTEXT_IN_EVERY_OPERATOR} by default. A service sets it to {@code limited}, Spring
   * Boot's own default, to keep the context only in handle and tap.
   */
  public static final String REACTOR_CONTEXT_PROPAGATION = "spring.reactor.context-propagation";

  /** Reactor puts the context on the thread of every operator, so every log line carries it. */
  public static final String REACTOR_CONTEXT_IN_EVERY_OPERATOR = "auto";

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
