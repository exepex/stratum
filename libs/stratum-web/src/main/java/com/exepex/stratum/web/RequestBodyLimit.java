package com.exepex.stratum.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.util.unit.DataUnit;

/**
 * Sets how large the request body of the requests a controller method answers may be. It wins over
 * {@code stratum.web.body-limit.routes} and {@code stratum.web.body-limit.default-limit}, so one
 * API, such as an upload, can accept more than the rest:
 *
 * <pre>{@code
 * @RequestBodyLimit(20)
 * @PostMapping("/documents")
 * DocumentId upload(InputStream body) { ... }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequestBodyLimit {

  /**
   * How large the body may be, in {@link #unit()}. 0 or less means no limit.
   *
   * @return the limit
   */
  long value();

  /**
   * The unit of {@link #value()}.
   *
   * @return the unit, megabytes unless set
   */
  DataUnit unit() default DataUnit.MEGABYTES;
}
