package com.exepex.stratum.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * Sets how long the requests a controller method answers may take until their response starts. It
 * wins over {@code stratum.web.timeout.routes} and {@code stratum.web.timeout.default-limit}, so
 * one slow API, such as a report, can have more time than the rest:
 *
 * <pre>{@code
 * @ResponseTimeout(60)
 * @GetMapping("/reports/{reportId}")
 * Report report(@PathVariable String reportId) { ... }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ResponseTimeout {

  /**
   * How long the request may take, in {@link #unit()}. 0 or less means no limit.
   *
   * @return the limit
   */
  long value();

  /**
   * The unit of {@link #value()}.
   *
   * @return the unit, seconds unless set
   */
  TimeUnit unit() default TimeUnit.SECONDS;
}
