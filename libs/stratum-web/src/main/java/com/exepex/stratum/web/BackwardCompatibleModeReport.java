package com.exepex.stratum.web;

import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Tells the team at startup, while backward-compatible mode is on, what the mode does and how to
 * leave it, and warns when Spring Boot's error body, which the mode keeps, shows stack traces or
 * exception classes to API callers: a security leak that the service's own {@code
 * spring.web.error.include-stacktrace} or {@code include-exception} turned on.
 */
@Slf4j
@RequiredArgsConstructor
public class BackwardCompatibleModeReport implements SmartInitializingSingleton {

  /** Spring Boot's setting that puts the stack trace in its error body. */
  static final String INCLUDE_STACKTRACE = "spring.web.error.include-stacktrace";

  /** Spring Boot's setting that puts the exception class in its error body. */
  static final String INCLUDE_EXCEPTION = "spring.web.error.include-exception";

  /** The web module's settings. */
  private final WebProperties webProperties;

  /** Where Spring Boot's settings are read from. */
  private final Environment environment;

  /** Logs the INFO line, and the warning when either setting shows anything, in the mode only. */
  @Override
  public void afterSingletonsInstantiated() {
    if (!webProperties.backwardCompatible()) {
      return;
    }
    if (log.isInfoEnabled()) {
      log.info(
          "{} is true: errors that Spring answers keep Spring Boot's body unless the consumer sends"
              + " Accept: application/problem+json, and no time or body size limit applies unless"
              + " set. The counter {} shows which routes still answer with Spring Boot's body; set"
              + " the property to false once it stays at 0.",
          WebConstants.BACKWARD_COMPATIBLE,
          WebConstants.BOOT_ERROR_BODY_COUNTER);
    }
    var binder = Binder.get(environment);
    var stackTrace =
        binder.bind(INCLUDE_STACKTRACE, IncludeStackTrace.class).orElse(IncludeStackTrace.NEVER);
    var exception = binder.bind(INCLUDE_EXCEPTION, Boolean.class).orElse(false);
    if ((stackTrace != IncludeStackTrace.NEVER || exception) && log.isWarnEnabled()) {
      log.warn(
          "Security leak: errors keep Spring Boot's body because {} is true, and {}={} with {}={}"
              + " show stack traces or exception classes to API callers. Set them to never and"
              + " false.",
          WebConstants.BACKWARD_COMPATIBLE,
          INCLUDE_STACKTRACE,
          stackTrace.name().toLowerCase(Locale.ROOT).replace('_', '-'),
          INCLUDE_EXCEPTION,
          exception);
    }
  }

  /**
   * The values of Spring Boot's {@code include-stacktrace}, bound the way Spring Boot binds them.
   */
  private enum IncludeStackTrace {
    /** Never shows the stack trace, Spring Boot's default. */
    NEVER,
    /** Always shows it. */
    ALWAYS,
    /** Shows it when the request has the {@code trace} parameter. */
    ON_PARAM
  }
}
