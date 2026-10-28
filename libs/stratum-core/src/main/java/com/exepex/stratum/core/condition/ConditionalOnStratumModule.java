package com.exepex.stratum.core.condition;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * Creates the annotated auto-configuration or bean only when its Stratum module is switched on. The
 * switch is the property {@code stratum.<module>.enabled}, for example {@code stratum.web.enabled},
 * set in {@code application.properties}, {@code application.yml}, an environment variable or any
 * other Spring Boot property source.
 *
 * <p>Every Stratum starter puts this on each of its auto-configurations, so that one property turns
 * the whole module on or off. When the property is not set, the module is off: a service switches
 * on each module it uses with {@code stratum.<module>.enabled=true}, even when a bundle starter put
 * the module on the classpath. A value that is not a boolean, such as {@code maybe}, stops the
 * application at startup.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Conditional(OnStratumModuleCondition.class)
public @interface ConditionalOnStratumModule {

  /**
   * The module's name as it appears in its properties, in kebab-case: {@code web}, {@code
   * http-client} or {@code oauth2-client}.
   *
   * @return the module's name
   */
  String value();
}
