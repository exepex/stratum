package com.exepex.stratum.core.reactor;

import com.exepex.stratum.core.condition.StratumModuleConstants;
import lombok.experimental.UtilityClass;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import reactor.core.publisher.Hooks;

/**
 * Makes Reactor carry the logging and tracing context into every operator when {@code
 * spring.reactor.context-propagation} is {@code auto}. Spring Boot does this only when its
 * spring-boot-reactor module is present, which comes with WebFlux and WebClient; a service that
 * uses Reactor without them, for example through R2DBC or Reactor Kafka, would otherwise lose the
 * trace and workflow fields after a thread change.
 */
@UtilityClass
public class ReactorOperatorContextPropagation {

  /** A Reactor class, present only when the service uses Reactor. */
  private static final String REACTOR_HOOKS_CLASS_NAME = "reactor.core.publisher.Hooks";

  /**
   * Turns on Reactor's context propagation in every operator when the service uses Reactor and the
   * setting is {@code auto}. Calling it again, or after Spring Boot did, changes nothing.
   *
   * @param environment the application's environment, with Stratum's defaults already added
   */
  public static void enableWhenSetToEveryOperator(Environment environment) {
    // Spring Boot reads the setting ignoring case.
    var settingIsEveryReactorOperator =
        String.CASE_INSENSITIVE_ORDER.compare(
                StratumModuleConstants.REACTOR_CONTEXT_IN_EVERY_OPERATOR,
                environment.getProperty(StratumModuleConstants.REACTOR_CONTEXT_PROPAGATION, ""))
            == 0;
    if (settingIsEveryReactorOperator
        && ClassUtils.isPresent(REACTOR_HOOKS_CLASS_NAME, ClassUtils.getDefaultClassLoader())) {
      Hooks.enableAutomaticContextPropagation();
    }
  }
}
