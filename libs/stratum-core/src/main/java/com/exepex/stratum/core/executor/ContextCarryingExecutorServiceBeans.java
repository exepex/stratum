package com.exepex.stratum.core.executor;

import com.exepex.stratum.core.api.StratumInternal;
import io.micrometer.context.ContextExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * Wraps the pools the service declares as beans with context-carrying wrappers, so that each task
 * keeps the logging and tracing context of the code that submitted it. A scheduled pool passes the
 * context only to one-shot tasks ({@link OneShotContextScheduledExecutorService}). A pool is
 * wrapped when its bean is declared as a type the wrapper also has, such as {@code Executor},
 * {@code ExecutorService} or {@code ScheduledExecutorService}. A bean declared as a class, such as
 * {@code ThreadPoolExecutor}, is left alone, because the wrapper is not that class and the code
 * that injects it would break.
 */
@StratumInternal
@RequiredArgsConstructor
public class ContextCarryingExecutorServiceBeans implements BeanPostProcessor {

  /** The service's beans, to read the type each bean is declared as. */
  private final ConfigurableListableBeanFactory beanFactory;

  /**
   * Returns the pool wrapped when its declared type allows it and it is not wrapped yet, or the
   * bean unchanged.
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
          when isDeclaredAsTypeOf(beanName, OneShotContextScheduledExecutorService.class) ->
          new OneShotContextScheduledExecutorService(scheduledPool);
      case ExecutorService pool when isDeclaredAsTypeOf(beanName, ContextExecutorService.class) ->
          ContextExecutorService.wrap(pool);
      default -> bean;
    };
  }

  /**
   * Returns whether the type the bean's definition declares, as a factory method's return type
   * does, is one the wrapper has, so the wrapped bean can still be injected where it is used.
   */
  private boolean isDeclaredAsTypeOf(String beanName, Class<?> wrapperType) {
    if (!beanFactory.containsBeanDefinition(beanName)) {
      return false;
    }
    var declaredType = beanFactory.getMergedBeanDefinition(beanName).getResolvableType().resolve();
    return declaredType != null && declaredType.isAssignableFrom(wrapperType);
  }
}
