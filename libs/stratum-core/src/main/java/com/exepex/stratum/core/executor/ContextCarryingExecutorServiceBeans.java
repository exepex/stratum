package com.exepex.stratum.core.executor;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextScheduledExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * Wraps the {@code ExecutorService} and {@code ScheduledExecutorService} beans the service declares
 * with Micrometer's context-carrying wrappers, so that each task keeps the logging and tracing
 * context of the code that submitted it. Only a bean declared as one of those interfaces is
 * wrapped: the wrapper is not, for example, a {@code ThreadPoolExecutor}, so wrapping a bean
 * declared as that class would break the code that injects it.
 */
@RequiredArgsConstructor
public class ContextCarryingExecutorServiceBeans implements BeanPostProcessor {

  /** The service's beans, to read the type each bean is declared as. */
  private final ConfigurableListableBeanFactory beanFactory;

  /**
   * Returns the bean wrapped when it is declared as {@code ExecutorService} or {@code
   * ScheduledExecutorService} and is not wrapped yet, or the bean unchanged.
   *
   * @param bean the bean
   * @param beanName its name
   * @return the bean, possibly wrapped
   */
  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    return switch (bean) {
      case ContextExecutorService<?> alreadyWrapped -> alreadyWrapped;
      case ScheduledExecutorService scheduledPool
          when isDeclaredAs(beanName, ScheduledExecutorService.class) ->
          ContextScheduledExecutorService.wrap(scheduledPool);
      case ExecutorService pool when isDeclaredAs(beanName, ExecutorService.class) ->
          ContextExecutorService.wrap(pool);
      default -> bean;
    };
  }

  /**
   * Returns whether the bean's definition declares exactly this type, as a factory method's return
   * type does.
   */
  private boolean isDeclaredAs(String beanName, Class<?> declaredType) {
    return beanFactory.containsBeanDefinition(beanName)
        && declaredType.equals(
            beanFactory.getMergedBeanDefinition(beanName).getResolvableType().resolve());
  }
}
