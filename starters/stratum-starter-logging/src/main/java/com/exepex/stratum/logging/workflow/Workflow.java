package com.exepex.stratum.logging.workflow;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs the annotated method as a named workflow, as {@link Workflows#call} does. It works only when
 * AspectJ is on the classpath and the method is called through its Spring bean; otherwise it is
 * ignored.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Workflow {

  /**
   * The workflow's fixed name, such as {@code ORDER_CHECKOUT}: 1 to 128 letters, digits, {@code -},
   * {@code _}, {@code .} or {@code :}.
   *
   * @return the name
   */
  String value();
}
