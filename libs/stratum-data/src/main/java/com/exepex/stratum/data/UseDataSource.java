package com.exepex.stratum.data;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs a JPA repository on a named pool of {@code stratum.data.sources}, or on {@code main}. It
 * wins over the pool's {@code packages}. A transaction stays on one pool: a call for another pool
 * fails with {@code SX-DATA-1008}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface UseDataSource {

  /**
   * Returns the pool's name, such as {@code reporting}, or {@code main}.
   *
   * @return the pool's name
   */
  String value();
}
